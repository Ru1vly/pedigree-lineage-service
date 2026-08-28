# Operations runbook

Bring it up, deploy it, and work out what's wrong when it misbehaves.

## Local

```bash
docker compose up -d          # postgres, kafka, connect, debezium connector, redis, zipkin, app
docker compose logs -f app
```

Compose wires the whole ingress path, including registering the Debezium outbox connector. The
`connector-setup` container is idempotent — it checks whether `lineage-outbox-connector` already
exists before POSTing, because Kafka Connect keeps its own state in `*_connect_configs` and
re-registering returns 409. A restart won't fail the compose run.

What comes up:

| Service | Port | For |
|---|---|---|
| app | 8080 | the service |
| postgres | 5432 | tasks, outbox, audit logs |
| kafka | 9092 / 19092 internal | `lineage.query.events`, `.dlt` |
| connect | 8083 | Debezium |
| redis | 6379 | processing locks, status cache |
| zipkin | 9411 | traces |

No external identity provider runs locally, so token validation falls back to the shared HS256
secret and `DevTokenController` mints tokens at `POST /api/v1/lineage/dev/token`. That
controller does not exist outside non-production profiles. Startup will warn about the insecure
defaults; those warnings are the point.

Verify the CDC path is actually live before you debug anything downstream of it:

```bash
curl -s localhost:8083/connectors/lineage-outbox-connector/status | jq
docker compose exec kafka kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic lineage.query.events --from-beginning --max-messages 5
```

If a task sits at SUBMITTED forever, this is almost always where the problem is. Nothing in the
application publishes to Kafka — the only write is an outbox row, and Debezium does the rest. A
dead connector means messages simply never appear, and the application looks perfectly healthy
while doing nothing.

## Deploying

```bash
scripts/validate_manifests.sh                    # renders and asserts the chart
helm upgrade --install pedigree-lineage helm/pedigree-lineage -f helm/pedigree-lineage/values-prod.yaml
```

ArgoCD manifests are in `argocd/`, Flux in `flux/`. Both target the same chart.

Before a real deployment, replace every placeholder credential. Better: set the matching
`existingSecret` / `existingAppSecret` fields and point them at Secrets your platform already
manages, so the chart references those instead of templating its own. The application refuses to
start under the `production` profile with repo-default secrets or with no identity provider
configured — see [`CONFIGURATION.md`](CONFIGURATION.md).

## Autoscaling

KEDA scales workers on Kafka consumer-group lag for `lineage.query.events`, group
`lineage-worker-group`, polled every 5 seconds. Target is 1000 lag per pod, between 2 and 12
replicas.

```bash
kubectl get scaledobject pedigree-lineage-worker-scaler
kubectl get hpa keda-hpa-pedigree-lineage-worker-scaler
kubectl describe scaledobject pedigree-lineage-worker-scaler   # trigger errors show up here
```

**12 is not arbitrary and raising it alone does nothing.** Kafka gives each partition to at most
one consumer in a group. Beyond the partition count of `lineage.query.events` (see
`kafka.topicPartitions`, currently 12) additional pods join the group, receive no assignment,
and idle while consuming their resource requests. To scale further, repartition the topic first,
then raise `kafka.topicPartitions` and `keda.maxReplicaCount` together.

If lag is climbing and replicas are not, check in this order: the ScaledObject's trigger status
for authentication or connectivity errors; whether the consumer group actually exists
(`kafka-consumer-groups.sh --describe --group lineage-worker-group`); and whether you have
already hit the partition ceiling.

`excludePersistentLag: true` is set so a partition whose lag never drains — a poison record
parked ahead of the committed offset — doesn't pin the tier scaled out forever.

## Canary deployments

Argo Rollouts steps 10% → 2m → analysis → 30% → 60% → 100%. The analysis fails the rollout on
HTTP 5xx rate above 0.5%, worker CPU above 90%, or **any** arrival on
`lineage.query.events.dlt`.

That last one used to query a RabbitMQ DLQ metric. The series was permanently absent, so the
check was permanently green and a canary that dead-lettered every record would have been
promoted to 100%. If you change topic names, change the analysis query with them — a gate that
cannot fail is worse than no gate, because it sells you confidence you have not earned.

## Failure modes

**Tasks stuck at SUBMITTED.** The CDC path. Check the Debezium connector status and whether
`lineage.query.events` is receiving anything. Check the outbox table is filling
(`select count(*) from transactional_outbox`).

**Tasks FAILED with `MAX_RETRIES_EXCEEDED_DLQ`.** This is the generic backstop, meaning the
record reached the dead-letter topic *without* a specific cause having been recorded. Since the
transaction rework that should be rare — the pipeline records the real error code and a
`LINEAGE_QUERY_FAILED` audit row before it rethrows. Seeing the generic code a lot means either
the failure happened outside the pipeline's own error handling, or something regressed in the
transaction boundaries. Read [`WHAT_WAS_BROKEN.md`](WHAT_WAS_BROKEN.md) section 1 before
assuming it's fine.

**Tasks FAILED with `PIPELINE_EXECUTION_ERROR`.** Normal terminal failure. `error_message` on
the task row and the audit row's `details` both carry the actual cause. Retries were exhausted
first — `retry_count` against `max_retries` tells you how many attempts it took.

**Nothing processes and the logs mention locks.** Redis. A failed lock acquisition is treated as
a pipeline failure by design, because running without the lock risks two workers on one task.
Locks are `lock:lineage:processing:{txId}` with a 10 minute TTL and self-expire; they are
released with a compare-and-delete against a per-attempt token, so a slow worker cannot delete
someone else's.

**`IllegalStateException: Failed to decrypt ...`.** Vault is unreachable or the ciphertext
predates a key change. This is deliberate: `decrypt` used to return the ciphertext unchanged, so
callers stored and logged an encrypted blob believing it was a citizen's national ID. Loud
failure is correct. Fix Vault; do not "fix" the exception.

**401s after an identity provider change.** Tokens missing a national identity claim, or
carrying one that fails the TCKN checksum, are now rejected instead of being assigned a
placeholder. If a client suddenly can't authenticate, check what claims their tokens carry —
`national_id`, `tc_no` and `tckn` are all accepted, in that order.

**Startup fails with a config-guard message.** Working as intended. Under `production` the
service refuses to run with repo-default secrets, or with no external identity provider
configured. The message names the property.

## Observability

- `/actuator/health` — public, full details, liveness and readiness probes.
- `/actuator/prometheus` — public, scrape target.
- `/actuator/metrics`, and everything else under `/actuator` — **ROLE_ADMIN**.
- `env` and `loggers` are off the exposure list entirely by default. Opt in per environment with
  `MANAGEMENT_ENDPOINTS_EXPOSURE` only where an operator-only network path exists.
- Zipkin at `:9411` locally; sampling is 1.0, which you will want to lower in production.

Logs are JSON via logstash-logback with `traceId`, `transactionId` and `userId` in the MDC.
Context survives the Kafka handoff (`LineageTaskConsumer` restores it) and the async executor
(`MdcTaskDecorator`). The one gap is the SSE polling scheduler, which has no decorator because
`ThreadPoolTaskScheduler.setTaskDecorator` needs Spring 6.2 and this is 6.1.11 — those log lines
carry the transaction ID but no inherited trace.

Never log a raw TCKN. `TcknEncryptionService.mask` exists for this and the pipeline uses it.
