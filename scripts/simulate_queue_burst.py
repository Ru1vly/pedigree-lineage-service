#!/usr/bin/env python3
"""
Simulate RabbitMQ Queue Burst & KEDA Dynamic Autoscaling
Demonstrates scaling worker pods from 2 to 50 when 50,000 queries land in the queue.

By default this prints an illustrative example timeline, clearly labeled as such - it does not
measure anything. The ceil(queueDepth / targetLength) calculation is always real math. Pass
--live to instead read the ACTUAL current RabbitMQ queue depth (via the management API) and the
ACTUAL current worker replica count (via kubectl, if available), rather than only a scripted
narrative - this was previously the only mode, and it always printed the same fabricated
timeline regardless of whether a real cluster or broker existed.
"""

import sys
import time
import math
import json
import argparse
import subprocess
import urllib.request
import urllib.error
from base64 import b64encode


def fetch_live_queue_depth(management_url, username, password, queue_name, vhost="%2f"):
    url = f"{management_url}/api/queues/{vhost}/{queue_name}"
    request = urllib.request.Request(url)
    credentials = b64encode(f"{username}:{password}".encode()).decode()
    request.add_header("Authorization", f"Basic {credentials}")
    with urllib.request.urlopen(request, timeout=3) as response:
        data = json.loads(response.read())
    return data.get("messages", 0)


def fetch_live_worker_replicas(deployment_name, namespace):
    result = subprocess.run(
        ["kubectl", "get", "deployment", deployment_name, "-n", namespace,
         "-o", "jsonpath={.status.readyReplicas}"],
        capture_output=True, text=True, timeout=5,
    )
    if result.returncode != 0 or not result.stdout.strip():
        raise RuntimeError(result.stderr.strip() or "kubectl returned no ready-replica count")
    return int(result.stdout.strip())


def simulate_keda_scaling(initial_queue_depth, target_queue_length, min_replicas, max_replicas,
                           live, management_url, rabbitmq_user, rabbitmq_password, queue_name,
                           deployment_name, namespace):
    print("=" * 80)
    print(" 🚀 KEDA RABBITMQ QUEUE DEPTH DYNAMIC AUTOSCALING SIMULATOR")
    print("=" * 80)

    live_queue_depth = None
    live_replicas = None
    if live:
        try:
            live_queue_depth = fetch_live_queue_depth(management_url, rabbitmq_user, rabbitmq_password, queue_name)
            print(f"[LIVE] Queue depth read from {management_url} : {live_queue_depth:,} messages")
        except (urllib.error.URLError, TimeoutError, OSError, ValueError) as exc:
            print(f"[LIVE] Could not reach RabbitMQ management API at {management_url}: {exc}")

        try:
            live_replicas = fetch_live_worker_replicas(deployment_name, namespace)
            print(f"[LIVE] Ready worker replicas (kubectl, deployment/{deployment_name} -n {namespace}): {live_replicas}")
        except Exception as exc:
            print(f"[LIVE] Could not read live replica count via kubectl: {exc}")

    if live_queue_depth is not None:
        initial_queue_depth = live_queue_depth

    print(f"[*] Queue Depth Used Below:      {initial_queue_depth:,} messages"
          + (" (LIVE reading)" if live_queue_depth is not None else " (provided/default value, not measured)"))
    print(f"[*] KEDA Target Queue Length:    {target_queue_length:,} messages / pod")
    print(f"[*] Worker Min Replicas:        {min_replicas} pods")
    print(f"[*] Worker Max Replicas:        {max_replicas} pods")
    print("=" * 80)

    # This calculation is the one thing here that's always real math, live mode or not - it's
    # the same formula KEDA's RabbitMQ scaler applies.
    calculated_replicas = math.ceil(initial_queue_depth / target_queue_length) if initial_queue_depth > 0 else 0
    bounded_replicas = max(min_replicas, min(calculated_replicas, max_replicas))

    print("\n[+] KEDA Scaler Formula Evaluation:")
    print(f"    DesiredReplicas = ceil({initial_queue_depth:,} / {target_queue_length:,})")
    print(f"                    = {calculated_replicas} pods")
    print(f"    Bounded by [min={min_replicas}, max={max_replicas}] -> {bounded_replicas} pods")

    if live_replicas is not None:
        converged = "matches KEDA's computed target" if live_replicas == bounded_replicas else "KEDA may still be converging toward the target"
        print(f"\n[LIVE] Actual ready replicas right now: {live_replicas} ({converged})")

    if bounded_replicas == max_replicas:
        print(f"\n[🔥 BURST DETECTED] Queue depth of {initial_queue_depth:,} triggered MAXIMUM scaling limit of {max_replicas} worker pods!")

    if not live or (live_queue_depth is None and live_replicas is None):
        print("\n" + "-" * 80)
        print(" No live RabbitMQ/Kubernetes was queried" + ("" if live else " (pass --live to try)") + ".")
        print(" Everything below is an ILLUSTRATIVE EXAMPLE timeline, not a measurement.")
        print("-" * 80)
    else:
        print("\n" + "-" * 80)
        print(" The queue depth/replica readings above are live. The timeline below is still an")
        print(" ILLUSTRATIVE EXAMPLE - this script does not track scaling response over time.")
        print("-" * 80)

    print(f"\n{'Time (s)':<10} | {'Worker Pods':<12} | {'Queue Depth':<15} | {'Event Description (illustrative)'}")
    print("-" * 90)
    timeline_steps = [
        (0, min_replicas, initial_queue_depth, "Baseline background processing"),
        (5, 12, 48000, "KEDA polling interval (5s) triggered. Scale-up burst initiated (+10 pods)"),
        (15, 30, 42000, "HPA scale-up policy expanding capacity (+18 pods)"),
        (30, 50, 32000, "MAX REPLICAS (50 pods) reached. Processing at 15,000 msgs/min"),
        (60, 50, 17000, "50 worker pods consuming queue at high throughput"),
        (90, 50, 2000, "Queue depth draining rapidly"),
        (120, 50, 0, "Queue completely drained! (0 pending messages)"),
        (420, 50, 0, "Cooldown stabilization window (300s) active to prevent thrashing"),
        (480, 10, 0, "Gradual scale-down policy active (10% per minute)"),
        (720, min_replicas, 0, "Returned to baseline (2 pods)")
    ]
    for t, pods, q_len, status in timeline_steps:
        bar = "█" * int(pods / 2)
        print(f"{t:<10} | {pods:<12} | {q_len:<15,} | {status} {bar}")

    print("=" * 80)
    print("✅ SIMULATION COMPLETE.")
    print("=" * 80)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description='KEDA RabbitMQ Scaling Simulator')
    parser.add_argument('--queue-depth', type=int, default=50000,
                         help='Queue depth to use if --live is not passed or the live read fails (default: 50000)')
    parser.add_argument('--target-length', type=int, default=1000, help='Target queue length per pod (default: 1000)')
    parser.add_argument('--min-replicas', type=int, default=2)
    parser.add_argument('--max-replicas', type=int, default=50)
    parser.add_argument('--live', action='store_true',
                         help='Read the actual current RabbitMQ queue depth and worker replica count instead of only simulating')
    parser.add_argument('--management-url', default='http://localhost:15672',
                         help='RabbitMQ management API base URL (default matches docker-compose: http://localhost:15672)')
    parser.add_argument('--rabbitmq-user', default='guest')
    parser.add_argument('--rabbitmq-password', default='guest')
    parser.add_argument('--queue-name', default='lineage.query.ingress.queue')
    parser.add_argument('--deployment-name', default='pedigree-lineage-worker',
                         help='Worker Deployment name to read live replica count from via kubectl')
    parser.add_argument('--namespace', default='pedigree-lineage')
    args = parser.parse_args()

    simulate_keda_scaling(
        args.queue_depth, args.target_length, args.min_replicas, args.max_replicas,
        args.live, args.management_url, args.rabbitmq_user, args.rabbitmq_password,
        args.queue_name, args.deployment_name, args.namespace,
    )
