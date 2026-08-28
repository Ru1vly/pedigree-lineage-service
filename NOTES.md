# System Architecture & Operational Notes: Pedigree Lineage Service

> Deployment and scaling notes. For the code itself see [`docs/CODE_MAP.md`](docs/CODE_MAP.md),
> for configuration [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md), and for the runbook
> [`docs/OPERATIONS.md`](docs/OPERATIONS.md).

## 1. System Overview & Core Requirements

- **Domain**: e-Devlet heavy public-sector asynchronous task processing (Family Tree / Pedigree Query Pipeline).
- **Tech Stack**: Spring Boot 3, Java 21, PostgreSQL (Transactional Outbox + WAL), Debezium CDC, Apache Kafka, Redis, KEDA, Helm 3, ArgoCD / Argo Rollouts, FluxCD v2 / Flagger.
- **Pattern**: Asynchronous Request-Reply (`HTTP 202 Accepted` with `Location` and `Retry-After: 30` headers).
- **Core Requirement**: Scale worker pods dynamically on **Kafka consumer-group lag** using **KEDA**, so a sudden backlog scales workers from **2 up to the topic's partition count (12)**, combined with zero-downtime canary deployments.

---

## 2. KEDA Kafka Consumer-Lag Autoscaling Notes

### Why Lag Scaling?
- Traditional CPU/Memory HPA fails for queue-bound workers because pods waiting on network I/O, database locks, or external graph APIs maintain low CPU utilization while the backlog explodes.
- KEDA reads the committed lag of consumer group `lineage-worker-group` on `lineage.query.events` every **5 seconds**, preventing scaling lag.

### Mathematical Scaling Formula
$$\text{DesiredReplicas} = \min\left(\left\lceil \frac{\text{ConsumerLag}}{\text{LagThreshold}} \right\rceil,\ \text{Partitions}\right)$$

- **Lag Threshold**: `1000` events per pod.
- **Backlog Scenario (50,000 events)**: the raw calculation asks for 50 pods, but the partition
  count caps it: $$\min(50,\ 12) = 12 \text{ worker pods}$$
- **The cap is real, not conservatism.** Kafka assigns each partition to at most one consumer in
  a group, so pods past `kafka.topicPartitions` join and idle. Repartition the topic before
  raising `keda.maxReplicaCount`.

### HPA Behavior Configuration
- **Scale-Up**: `stabilizationWindowSeconds: 0` (instant reaction to burst), policy allowing up to `+48 pods` or `+1000%` in 15 seconds.
- **Scale-Down**: `stabilizationWindowSeconds: 300` (5-minute cooldown window to avoid pod thrashing), policy restricting scale down to `10%` per minute.

---

## 3. Helm Chart Architecture Notes

Chart Location: [`helm/pedigree-lineage`](file:///home/r1/Projects/pedigree-lineage-service/helm/pedigree-lineage)

### Key File Mapping
- `Chart.yaml`: Chart metadata and versioning (`1.0.0`).
- `values.yaml`: Base default configuration.
- `values-dev.yaml`: Dev environment overrides (`1 worker pod`, lower resource requests).
- `values-prod.yaml`: Production environment overrides (`2..12 worker pods`, `PDB` enabled).
- `values-canary.yaml`: Canary environment overrides (`useArgoRollout: true`, canary image tags).
- `templates/keda-scaledobject.yaml`: KEDA `ScaledObject` resource definition targeting Worker Deployment/Rollout.
- `templates/keda-triggerauth.yaml`: KEDA `TriggerAuthentication` for Kafka SASL/TLS. Rendered only when `keda.auth.enabled` is set - an in-cluster PLAINTEXT listener needs none, and an empty one makes the scaler fail rather than fall back to anonymous.
- `templates/worker-deployment.yaml`: Standard Kubernetes Deployment for workers.
- `templates/worker-rollout.yaml`: Argo Rollout CRD for canary releases.
- `templates/poddisruptionbudget.yaml`: Enforces high availability (`minAvailable: 2`) during node drains.

---

## 4. GitOps & Zero-Downtime Canary Notes

### ArgoCD & Argo Rollouts Strategy
- **Manifest Location**: [`argocd/`](file:///home/r1/Projects/pedigree-lineage-service/argocd)
- **Rollout Steps**:
  1. `10%` canary weight -> `2m` pause.
  2. Run `AnalysisTemplate` monitoring Prometheus metrics.
  3. `30%` weight -> `3m` pause.
  4. `60%` weight -> `2m` pause.
  5. `100%` full promotion.
- **AnalysisTemplate Metrics**:
  - HTTP 5xx error rate `< 0.5%`.
  - Kafka dead-letter topic (`lineage.query.events.dlt`) arrival rate `== 0`.
  - Worker CPU saturation `< 90%`.

### FluxCD v2 & Flagger Strategy
- **Manifest Location**: [`flux/`](file:///home/r1/Projects/pedigree-lineage-service/flux)
- **Flagger Canary**: Progressive delivery with 30s analysis intervals and automated `hey` load testing webhooks.

---

## 5. Graceful Shutdown & Zero Message Loss Notes

To ensure in-flight tasks finish gracefully when pods scale down or undergo rolling updates:
1. **Spring Boot Config**: `server.shutdown=graceful` and `spring.lifecycle.timeout-per-shutdown-phase=60s`.
2. **Kubernetes Spec**: `terminationGracePeriodSeconds: 90`.
3. **Kafka Listener Tuning**:
   - `spring.kafka.listener.concurrency: 3`
   - `spring.kafka.consumer.max-poll-records: 20`
   - `spring.kafka.consumer.auto-offset-reset: earliest`
4. **Shutdown Behavior**: On `SIGTERM` the listener container stops polling and active threads get up to 60s to finish and commit offsets. A pod that dies before committing causes a group rebalance, and another consumer re-reads from the last committed offset - the record is redelivered, not lost. Delivery is therefore at-least-once, and the Redis processing lock plus the pipeline's terminal-status check are what stop a redelivered record being processed twice.

---

## 6. Verification & Utility Commands

### 1. Validate All Manifests & Helm Templates
```bash
./scripts/validate_manifests.sh
```

### 2. Run KEDA Backlog Burst Simulation
```bash
python3 scripts/simulate_queue_burst.py --queue-depth 50000 --target-length 1000
```
Note the simulator models the uncapped formula. Real scaling is additionally capped at the
topic's partition count - see section 2.

### 3. Deploy via Helm (Prod)
```bash
helm upgrade --install pedigree-lineage helm/pedigree-lineage \
  -n pedigree-lineage --create-namespace \
  -f helm/pedigree-lineage/values-prod.yaml
```

### 4. Deploy via ArgoCD
```bash
kubectl apply -f argocd/application.yaml
```
