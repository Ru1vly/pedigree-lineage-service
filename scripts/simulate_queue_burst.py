#!/usr/bin/env python3
"""
Simulate a Kafka consumer-group lag burst and KEDA dynamic autoscaling.

Demonstrates scaling worker pods from 2 to 12 when a burst of queries lands on
lineage.query.events and the lineage-worker-group falls behind.

This script previously modelled a RabbitMQ queue and scaled to 50 pods. There is no AMQP broker
in this stack - the ingress path is Kafka plus Debezium CDC end to end (see docker-compose.yml,
KafkaConfig, and the KEDA ScaledObject, whose trigger type is `kafka`) - so it demonstrated a
feature the service does not have, against a broker it does not run, at a replica count the
deployment cannot reach. 12 is not a preference either: Kafka assigns each partition to at most
one consumer in a group, so with kafka.topicPartitions = 12 a 13th pod would join the group,
receive no assignment, and idle. See docs/OPERATIONS.md and helm/pedigree-lineage/values.yaml.

By default this prints an illustrative example timeline, clearly labeled as such - it does not
measure anything. The ceil(lag / lagThreshold) calculation is always real math. Pass --live to
instead read the ACTUAL current consumer-group lag (via kafka-consumer-groups) and the ACTUAL
current worker replica count (via kubectl, if available).
"""

import math
import json
import shutil
import argparse
import subprocess

# Defaults mirror helm/pedigree-lineage/values.yaml (keda.*, kafka.topicPartitions).
DEFAULT_TOPIC = "lineage.query.events"
DEFAULT_GROUP = "lineage-worker-group"
DEFAULT_LAG_THRESHOLD = 1000
DEFAULT_MIN_REPLICAS = 2
DEFAULT_MAX_REPLICAS = 12
DEFAULT_PARTITIONS = 12


def _kafka_consumer_groups_command(bootstrap_servers, group, container):
    """
    Prefer a kafka-consumer-groups on PATH; otherwise run it inside the compose Kafka container,
    which is where it exists in a default local checkout (apache/kafka:4.0.0 ships the CLI).
    """
    base = ["--bootstrap-server", bootstrap_servers, "--describe", "--group", group]

    for binary in ("kafka-consumer-groups", "kafka-consumer-groups.sh"):
        if shutil.which(binary):
            return [binary] + base

    if shutil.which("docker"):
        return ["docker", "exec", container, "/opt/kafka/bin/kafka-consumer-groups.sh"] + base

    raise RuntimeError(
        "no kafka-consumer-groups on PATH and no docker available to reach the broker container"
    )


def fetch_live_consumer_lag(bootstrap_servers, group, topic, container):
    """
    Total lag across every partition of `topic` for `group`, summed from the CLI's table output.

    Columns are: GROUP TOPIC PARTITION CURRENT-OFFSET LOG-END-OFFSET LAG ... - so LAG is index 5.
    Partitions with no committed offset report a literal '-' rather than a number.
    """
    command = _kafka_consumer_groups_command(bootstrap_servers, group, container)
    result = subprocess.run(command, capture_output=True, text=True, timeout=20)
    if result.returncode != 0:
        raise RuntimeError(result.stderr.strip() or "kafka-consumer-groups returned a non-zero exit")

    total_lag = 0
    matched_partitions = 0
    for line in result.stdout.splitlines():
        fields = line.split()
        if len(fields) < 6 or fields[1] != topic:
            continue
        matched_partitions += 1
        if fields[5].isdigit():
            total_lag += int(fields[5])

    if matched_partitions == 0:
        raise RuntimeError(
            f"consumer group '{group}' has no assigned partitions for topic '{topic}' "
            "(it may never have committed an offset yet)"
        )
    return total_lag, matched_partitions


def fetch_live_worker_replicas(deployment_name, namespace):
    result = subprocess.run(
        ["kubectl", "get", "deployment", deployment_name, "-n", namespace,
         "-o", "jsonpath={.status.readyReplicas}"],
        capture_output=True, text=True, timeout=10,
    )
    if result.returncode != 0 or not result.stdout.strip():
        raise RuntimeError(result.stderr.strip() or "kubectl returned no ready-replica count")
    return int(result.stdout.strip())


def simulate_keda_scaling(initial_lag, lag_threshold, min_replicas, max_replicas, partitions,
                          live, bootstrap_servers, group, topic, container,
                          deployment_name, namespace):
    print("=" * 80)
    print(" 🚀 KEDA KAFKA CONSUMER-GROUP LAG DYNAMIC AUTOSCALING SIMULATOR")
    print("=" * 80)

    live_lag = None
    live_partitions = None
    live_replicas = None

    if live:
        try:
            live_lag, live_partitions = fetch_live_consumer_lag(bootstrap_servers, group, topic, container)
            print(f"[LIVE] Consumer lag for group '{group}' on '{topic}': {live_lag:,} messages "
                  f"across {live_partitions} partition(s)")
        except Exception as exc:
            print(f"[LIVE] Could not read consumer-group lag from {bootstrap_servers}: {exc}")

        try:
            live_replicas = fetch_live_worker_replicas(deployment_name, namespace)
            print(f"[LIVE] Ready worker replicas (kubectl, deployment/{deployment_name} -n {namespace}): {live_replicas}")
        except Exception as exc:
            print(f"[LIVE] Could not read live replica count via kubectl: {exc}")

    if live_lag is not None:
        initial_lag = live_lag
    if live_partitions is not None:
        partitions = live_partitions

    print(f"[*] Consumer Lag Used Below:     {initial_lag:,} messages"
          + (" (LIVE reading)" if live_lag is not None else " (provided/default value, not measured)"))
    print(f"[*] KEDA lagThreshold:           {lag_threshold:,} messages / pod")
    print(f"[*] Worker Min Replicas:         {min_replicas} pods")
    print(f"[*] Worker Max Replicas:         {max_replicas} pods")
    print(f"[*] Topic Partitions:            {partitions} (hard ceiling on useful consumers)")
    print("=" * 80)

    # This calculation is the one thing here that is always real math, live mode or not - it is the
    # formula KEDA's Kafka scaler applies.
    calculated_replicas = math.ceil(initial_lag / lag_threshold) if initial_lag > 0 else 0
    bounded_replicas = max(min_replicas, min(calculated_replicas, max_replicas))
    useful_replicas = min(bounded_replicas, partitions)

    print("\n[+] KEDA Scaler Formula Evaluation:")
    print(f"    DesiredReplicas = ceil({initial_lag:,} / {lag_threshold:,})")
    print(f"                    = {calculated_replicas} pods")
    print(f"    Bounded by [min={min_replicas}, max={max_replicas}] -> {bounded_replicas} pods")

    if bounded_replicas > partitions:
        idle = bounded_replicas - partitions
        print(f"    ⚠ {idle} of those pods would receive no partition assignment and idle.")
        print(f"      Kafka gives each partition to at most one consumer in a group, so {partitions} "
              "is the ceiling.")
        print("      Raise kafka.topicPartitions first if more parallelism is genuinely needed.")
    print(f"    Consumers that can actually do work: {useful_replicas}")

    if live_replicas is not None:
        converged = ("matches KEDA's computed target" if live_replicas == bounded_replicas
                     else "KEDA may still be converging toward the target")
        print(f"\n[LIVE] Actual ready replicas right now: {live_replicas} ({converged})")

    if bounded_replicas == max_replicas:
        print(f"\n[🔥 BURST DETECTED] Lag of {initial_lag:,} saturated the {max_replicas}-pod ceiling "
              f"(one consumer per partition).")

    if not live or (live_lag is None and live_replicas is None):
        print("\n" + "-" * 80)
        print(" No live Kafka/Kubernetes was queried" + ("" if live else " (pass --live to try)") + ".")
        print(" Everything below is an ILLUSTRATIVE EXAMPLE timeline, not a measurement.")
        print("-" * 80)
    else:
        print("\n" + "-" * 80)
        print(" The lag/replica readings above are live. The timeline below is still an")
        print(" ILLUSTRATIVE EXAMPLE - this script does not track scaling response over time.")
        print("-" * 80)

    ceiling = min(max_replicas, partitions)
    print(f"\n{'Time (s)':<10} | {'Worker Pods':<12} | {'Consumer Lag':<15} | {'Event Description (illustrative)'}")
    print("-" * 100)
    timeline_steps = [
        (0, min_replicas, initial_lag, "Baseline background processing"),
        (5, min(6, ceiling), 48000, "KEDA polling interval (5s) elapsed. Scale-up burst initiated"),
        (15, min(9, ceiling), 42000, "HPA scale-up policy expanding capacity"),
        (30, ceiling, 32000, f"Partition ceiling ({ceiling} pods) reached; one consumer per partition"),
        (60, ceiling, 17000, f"{ceiling} worker pods consuming their partitions at full throughput"),
        (90, ceiling, 2000, "Consumer lag draining rapidly"),
        (120, ceiling, 0, "Lag fully drained (group caught up to the log end offset)"),
        (420, ceiling, 0, "Cooldown stabilization window (300s) active to prevent thrashing"),
        (480, min(4, ceiling), 0, "Gradual scale-down policy active"),
        (720, min_replicas, 0, f"Returned to baseline ({min_replicas} pods)"),
    ]
    for seconds, pods, lag, status in timeline_steps:
        bar = "█" * pods
        print(f"{seconds:<10} | {pods:<12} | {lag:<15,} | {status} {bar}")

    print("=" * 80)
    print("✅ SIMULATION COMPLETE.")
    print("=" * 80)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='KEDA Kafka consumer-group lag scaling simulator')
    parser.add_argument('--lag', type=int, default=50000,
                        help='Consumer lag to use if --live is not passed or the live read fails (default: 50000)')
    parser.add_argument('--lag-threshold', type=int, default=DEFAULT_LAG_THRESHOLD,
                        help=f'KEDA lagThreshold, messages per pod (default: {DEFAULT_LAG_THRESHOLD})')
    parser.add_argument('--min-replicas', type=int, default=DEFAULT_MIN_REPLICAS)
    parser.add_argument('--max-replicas', type=int, default=DEFAULT_MAX_REPLICAS,
                        help=f'Must stay <= topic partitions (default: {DEFAULT_MAX_REPLICAS})')
    parser.add_argument('--partitions', type=int, default=DEFAULT_PARTITIONS,
                        help=f'Partitions on the topic; the hard consumer ceiling (default: {DEFAULT_PARTITIONS})')
    parser.add_argument('--live', action='store_true',
                        help='Read the actual consumer-group lag and worker replica count instead of only simulating')
    parser.add_argument('--bootstrap-servers', default='localhost:9092',
                        help='Kafka bootstrap servers (default matches docker-compose: localhost:9092)')
    parser.add_argument('--group', default=DEFAULT_GROUP)
    parser.add_argument('--topic', default=DEFAULT_TOPIC)
    parser.add_argument('--kafka-container', default='lineage-kafka',
                        help='Compose container to exec the Kafka CLI in when it is not on PATH')
    parser.add_argument('--deployment-name', default='pedigree-lineage-worker',
                        help='Worker Deployment name to read live replica count from via kubectl')
    parser.add_argument('--namespace', default='pedigree-lineage')
    args = parser.parse_args()

    simulate_keda_scaling(
        args.lag, args.lag_threshold, args.min_replicas, args.max_replicas, args.partitions,
        args.live, args.bootstrap_servers, args.group, args.topic, args.kafka_container,
        args.deployment_name, args.namespace,
    )
