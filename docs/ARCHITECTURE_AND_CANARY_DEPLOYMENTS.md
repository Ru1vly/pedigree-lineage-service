# Autoscaling and canary deployments

Workers scale on Kafka consumer-group lag, not CPU. Deployments roll out progressively through
Argo Rollouts or Flagger, gated on metrics that can actually fail. This document covers why both
of those are built the way they are.

The ingress path is Kafka and Debezium CDC end to end. There is no AMQP broker in this stack, and
anything in older revisions of these manifests that scaled or health-checked against RabbitMQ was
pointing at infrastructure that does not exist - see [`WHAT_WAS_BROKEN.md`](WHAT_WAS_BROKEN.md)
section 7.

For the runbook version of this - what to check when lag climbs and replicas do not - see
[`OPERATIONS.md`](OPERATIONS.md).

---

## 1. How it fits together

```
                          ┌──────────────────────────┐
                          │  REST API Ingress Client │
                          └────────────┬─────────────┘
                                       │ POST /api/v1/lineage/queries
                                       ▼
                          ┌──────────────────────────┐
                          │  Pedigree Lineage API    │
                          └────────────┬─────────────┘
                                       │ INSERT transactional_outbox
                                       ▼
                          ┌──────────────────────────┐
                          │  PostgreSQL WAL          │
                          │  -> Debezium CDC         │
                          └────────────┬─────────────┘
                                       │ Outbox Event Router SMT
                                       ▼
                          ┌──────────────────────────┐
                          │      Kafka Broker        │
                          │Topic: lineage.query.events│
                          └────────────┬─────────────┘
                                       │
                ┌──────────────────────┴──────────────────────┐
                │ Consumer Group Lag (Polling Interval 5s)    │
                ▼                                             ▼
     ┌───────────────────────┐                    ┌───────────────────────┐
     │      KEDA Scaler      │                    │  Prometheus Metrics   │
     │(Target: 1000 lag/pod) │                    │(Error Rate & DLT Check│
     └──────────┬────────────┘                    └──────────┬────────────┘
                │ Scale Command (2 -> 12 Pods)               │ Canary Metrics
                ▼                                            ▼
   ┌─────────────────────────┐                   ┌────────────────────────┐
   │ Worker Pod Pool (2..12) │                   │ Argo Rollouts / Flux   │
   │  Lineage Task Consumer  │                   │ Canary Progressive Dev │
   └─────────────────────────┘                   └────────────────────────┘
```

---

## 2. Why CPU-based scaling is the wrong signal here

A worker in this system spends most of its wall-clock time waiting - on the census graph backend,
on PostgreSQL, on Vault. Waiting costs almost no CPU. So a pod that is completely saturated in
the only sense that matters, having no capacity to accept more work, looks about 20% busy to an
HPA and does not trigger a scale-out. The backlog grows while the autoscaler reports everything
is fine.

When CPU does eventually spike, the default 5-minute stabilization window delays new pods further,
by which point the backlog is deep enough that catching up is its own problem.

The mistake is measuring the worker instead of the work. Consumer lag is a direct measurement of
how far behind the system is, it responds immediately, and it does not care whether the
bottleneck is CPU, network or a slow downstream.

### Scaling on consumer lag
KEDA reads the committed lag of consumer group `lineage-worker-group` on topic `lineage.query.events`:
- **Instant Response**: Polling interval set to **5 seconds**.
- **Deterministic Scale Calculation**:
  $$\text{DesiredReplicas} = \min\left(\left\lceil \frac{\text{ConsumerLag}}{\text{LagThreshold}} \right\rceil,\ \text{Partitions}\right)$$
  For a **50,000 event backlog** with `lagThreshold = 1000`, the raw calculation asks for 50 pods but the
  ceiling is the topic's partition count:
  $$\text{DesiredReplicas} = \min(50,\ 12) = 12 \text{ worker pods}$$
- **Why the ceiling is real**: Kafka assigns each partition to at most one consumer within a group. Pods
  beyond the partition count join the group and idle - unlike a RabbitMQ queue, where any number of
  consumers can compete for the same queue. Scaling further means repartitioning the topic first, and
  `kafka.topicPartitions` in `values.yaml` documents that ceiling alongside `keda.maxReplicaCount`.

---

## 3. KEDA ScaledObject and HPA tuning

The `ScaledObject` manifest (`helm/pedigree-lineage/templates/keda-scaledobject.yaml`) enforces:

```yaml
apiVersion: keda.sh/v1alpha1
kind: ScaledObject
metadata:
  name: pedigree-lineage-worker-scaler
spec:
  scaleTargetRef:
    apiVersion: apps/v1 # or argoproj.io/v1alpha1 when using Argo Rollout
    kind: Deployment
    name: pedigree-lineage-worker
  minReplicaCount: 2
  maxReplicaCount: 12
  pollingInterval: 5
  cooldownPeriod: 300
  advanced:
    horizontalPodAutoscalerConfig:
      behavior:
        scaleUp:
          stabilizationWindowSeconds: 0 # Instant burst response
          selectPolicy: Max
          policies:
            - type: Pods
              value: 48        # Can add up to +48 pods in 15 seconds
              periodSeconds: 15
            - type: Percent
              value: 1000      # 10x scale expansion
              periodSeconds: 15
        scaleDown:
          stabilizationWindowSeconds: 300 # 5 min cooldown window
          selectPolicy: Min
          policies:
            - type: Percent
              value: 10        # Gradual scale down (10% per minute)
              periodSeconds: 60
  triggers:
    - type: kafka
      metadata:
        bootstrapServers: "kafka.infrastructure.svc.cluster.local:9092"
        consumerGroup: "lineage-worker-group"
        topic: "lineage.query.events"
        lagThreshold: "1000"
        activationLagThreshold: "1"
        offsetResetPolicy: earliest
        excludePersistentLag: "true"
      # authenticationRef is rendered only when keda.auth.enabled is set; an in-cluster
      # PLAINTEXT listener needs none.
```

---

## 4. Canary deployments

### 4.1 Argo Rollouts

Worker updates roll out in weighted steps, with an automated analysis gate after the first one:
1. **Step 1 (10% Weight)**: 10% of worker workload routed to the new canary image version (`1.1.0-canary`).
2. **Step 2 (2m Pause)**: Allows background metrics to accumulate.
3. **Step 3 (AnalysisRun)**: Evaluates `pedigree-worker-canary-analysis` Prometheus queries:
   - `http_server_requests` 5xx error rate < 0.5%.
   - Kafka dead-letter-topic (`lineage.query.events.dlt`) arrival rate == 0.
   - Worker CPU saturation < 90%.
4. **Step 4 (30% Weight & 3m Pause)**: Expands canary footprint.
5. **Step 5 (60% Weight & 2m Pause)**: Broad validation phase.
6. **Step 6 (100% Weight)**: Final promotion of canary release to stable.

The dead-letter check is the one that matters most, and it is the one that was broken. It
previously queried a RabbitMQ DLQ metric that no longer exists in this stack, so the series was
permanently absent, the condition was permanently satisfied, and a canary that was dead-lettering
every single record would have been promoted to 100%.

A gate that cannot fail is worse than no gate. No gate is honest about providing no safety; a
permanently-green gate sells you confidence you have not earned. If you rename a topic, move the
analysis query with it, and check that the metric actually reports something before trusting the
result.

### 4.2 FluxCD v2 and Flagger

Flagger monitors worker deployments continuously:
- **Interval**: 30s analysis steps.
- **Threshold**: Maximum 5 failed checks before automated rollback.
- **Webhooks**: Automated `hey` load testing against canary health endpoints prior to traffic weight increase.

---

## 5. Graceful shutdown

To avoid losing in-flight work when the tier scales down or pods are replaced during a canary:
1. **Spring Boot Lifecycle Timeout**:
   ```yaml
   server:
     shutdown: graceful
   spring:
     lifecycle:
       timeout-per-shutdown-phase: 60s
   ```
2. **Pod Termination Grace Period**: `terminationGracePeriodSeconds: 90` in Kubernetes Pod spec.
3. **Kafka Listener Concurrency & Offset Commits**:
   ```yaml
   spring:
     kafka:
       consumer:
         group-id: lineage-worker-group
         auto-offset-reset: earliest
         max-poll-records: 20
       listener:
         concurrency: 3
   ```
   When a `SIGTERM` signal is received:
   - The Spring Kafka listener container stops polling for *new* records.
   - Active worker threads are given up to 60 seconds to finish in-flight tasks and commit their offsets.
   - If a pod dies before committing, the group rebalances and another consumer re-reads from the last
     committed offset, so the record is redelivered rather than lost. Redelivery makes at-least-once the
     delivery guarantee here: the pipeline's Redis processing lock and its terminal-status check are what
     keep a redelivered record from being processed twice.

---

## 6. Commands

### Render and assert the chart
```bash
./scripts/validate_manifests.sh
```

### Simulate a backlog burst
```bash
python3 scripts/simulate_queue_burst.py --queue-depth 50000 --target-length 1000
```
The simulator models the uncapped formula and will happily tell you 50 pods. Real scaling is
additionally capped at the partition count - see section 2.

### Deploy via Helm
```bash
# Production Deployment with KEDA
helm upgrade --install pedigree-lineage helm/pedigree-lineage \
  -n pedigree-lineage --create-namespace \
  -f helm/pedigree-lineage/values-prod.yaml

# Canary Release Deployment with Argo Rollouts
helm upgrade --install pedigree-lineage helm/pedigree-lineage \
  -n pedigree-lineage \
  -f helm/pedigree-lineage/values-canary.yaml
```

### Deploy via ArgoCD
```bash
kubectl apply -f argocd/application.yaml
```

### Deploy via FluxCD v2
```bash
kubectl apply -k flux/
```

### Check why it is not scaling
```bash
kubectl describe scaledobject pedigree-lineage-worker-scaler   # trigger auth/connectivity errors
kubectl get hpa keda-hpa-pedigree-lineage-worker-scaler
kafka-consumer-groups.sh --bootstrap-server <broker> --describe --group lineage-worker-group
```
In order: trigger errors, then whether the consumer group exists at all, then whether you have
already hit the partition ceiling.
