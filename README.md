# Pedigree Lineage Service

Asynchronous task processing for e-Devlet-style pedigree/family-tree queries. Spring Boot 3,
Java 21, PostgreSQL, Debezium CDC, Kafka, Redis.

A citizen submits a lineage query and gets `202 Accepted` immediately. The work happens on a
worker tier that scales on Kafka consumer lag. The citizen polls, or streams progress over SSE,
until there's a certified document to download.

The interesting parts are the transactional outbox with log-based CDC (the application never
publishes to a broker), the transaction boundaries around the worker pipeline, and TCKN
encryption at rest. All three are documented, and two of them are documented because they were
wrong once — see [`docs/WHAT_WAS_BROKEN.md`](docs/WHAT_WAS_BROKEN.md).

## How it fits together

```
                           +------------------------------------+
                           |    OAuth2 / OIDC Identity Provider |
                           +-----------------+------------------+
                                             | Bearer JWT
                                             v
+------------------+  POST /api/v1/lineage/queries  +-----------------------+
| Citizen / Client | -----------------------------> |  Lineage Ingress API  |
+------------------+ <----------------------------- +-----------+-----------+
       ^              HTTP 202 Accepted (Location)              |
       |               Retry-After: 30                          | Save Entity & Outbox
       |                                                        v                (one transaction)
       |               GET /api/v1/lineage/queries/{txId}   +-----------------------+
       +--------------------------------------------------- | PostgreSQL (Database) |
                                                            +-----------+-----------+
                                                                        | WAL (Write-Ahead Log)
                                                                        v
                                                            +-----------------------+
                                                            | Debezium (Kafka Connect)|
                                                            | pgoutput -> Outbox Event|
                                                            | Router SMT              |
                                                            +-----------+-----------+
                                                                        | zero polling, ms latency
                                                                        v
                                                            +-----------------------+
                                                            |   Apache Kafka Topic  |
                                                            | lineage.query.events  |
                                                            +-----------+-----------+
                                                                        |
                                                                        v
                                                            +-----------------------+
                                                            | Lineage Task Worker   |
                                                            | (3-Phase Orchestration|
                                                            +-----------+-----------+
                                                                        | Updates State & Cache
                                                                        v
                                                            +-----------------------+
                                                            |   Redis Cache & DB    |
                                                            +-----------------------+
```

The identity provider box is real only if you configure one. Without
`app.security.jwt.jwk-set-uri` or `issuer-uri` the service validates tokens with a shared HS256
secret, which is a local-development mode and nothing more. It refuses to start that way under
the `production` profile.

## Running it

Needs Java 21, Maven 3.9+, Docker.

```bash
mvn clean test
docker compose up --build
```

Compose brings up Postgres (`wal_level=logical`), Kafka, Kafka Connect with the Debezium outbox
connector registered automatically, Redis, Zipkin, and the app on `:8080`.

No identity provider runs locally, so grab a token from the dev endpoint — it mints a signed JWT
for any identity with no credential check, exists only outside the `production` profile, and
shouts about itself in the startup logs:

```bash
TOKEN=$(curl -sX POST http://localhost:8080/api/v1/lineage/dev/token \
  -H 'Content-Type: application/json' -d '{"roles":["USER"]}' | jq -r .token)
```

Submit a query:

```bash
curl -X POST http://localhost:8080/api/v1/lineage/queries \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -H "X-Trace-Id: trace-12345" \
  -d '{
    "nationalId": "12345678950",
    "generationsDepth": 3,
    "includeCertificates": true,
    "documentFormat": "PDF",
    "idempotencyKey": "req-uuid-98765"
  }'
```

`nationalId` is checked against the real TCKN checksum algorithm by `@ValidTckn`, so you cannot
use a plausible-looking number you made up — `12345678901` fails, and this README used to tell
you to send exactly that. `12345678950` passes. So does the default the dev token endpoint uses.

```json
{
  "transactionId": "b27b705c-4d1f-41a5-b1db-95504d0623af",
  "status": "SUBMITTED",
  "currentPhase": "INITIATED",
  "progressPercentage": 0,
  "retryAfterSeconds": 30,
  "createdAt": "2026-08-20T10:30:00Z",
  "statusUrl": "/api/v1/lineage/queries/b27b705c-4d1f-41a5-b1db-95504d0623af"
}
```

Then poll it:

```bash
curl http://localhost:8080/api/v1/lineage/queries/b27b705c-4d1f-41a5-b1db-95504d0623af \
  -H "Authorization: Bearer $TOKEN"
```

```json
{
  "transactionId": "b27b705c-4d1f-41a5-b1db-95504d0623af",
  "status": "COMPLETED",
  "currentPhase": "FINISHED",
  "phaseDescription": "Lineage Processing Finished",
  "progressPercentage": 100,
  "retryAfterSeconds": 0,
  "createdAt": "2026-08-20T10:30:00Z",
  "updatedAt": "2026-08-20T10:30:15Z",
  "completedAt": "2026-08-20T10:30:15Z",
  "result": {
    "rootPerson": {
      "nationalIdMasked": "123*****950",
      "firstName": "AHMET",
      "lastName": "YILMAZ",
      "fatherName": "MEHMET",
      "motherName": "FATMA",
      "birthDate": "1985-04-12",
      "birthPlace": "ANKARA",
      "status": "ALIVE",
      "relation": "SELF"
    },
    "generations": [
      {
        "generationLevel": 1,
        "relationLabel": "1st Generation (Parents)",
        "members": [
          {
            "nationalIdMasked": "382*****102",
            "firstName": "MEHMET",
            "lastName": "YILMAZ",
            "fatherName": "MUSTAFA",
            "motherName": "AYŞE",
            "birthDate": "1958-08-20",
            "birthPlace": "KONYA",
            "status": "ALIVE",
            "relation": "FATHER"
          }
        ]
      }
    ],
    "totalAncestorsFound": 3,
    "verificationSealHash": "SHA256-A9C8E7F12D34B567890EF1234567890ABCDEF",
    "documentDownloadUrl": "/api/v1/lineage/documents/e4d3c2b1/download"
  },
  "resultDownloadUrl": "/api/v1/lineage/documents/e4d3c2b1/download"
}
```

If a task sits at `SUBMITTED` and never moves, the CDC path is down, not the application.
Nothing here publishes to Kafka — the only write is an outbox row. Check
`curl -s localhost:8083/connectors/lineage-outbox-connector/status`.

## Endpoints

| Method | Path | Access |
|---|---|---|
| `POST` | `/api/v1/lineage/queries` | authenticated, rate limited 10/min per caller |
| `GET` | `/api/v1/lineage/queries/{txId}` | owner or admin |
| `DELETE` | `/api/v1/lineage/queries/{txId}` | owner or admin |
| `GET` | `/api/v1/lineage/queries/{txId}/stream` | owner or admin, SSE progress |
| `GET` | `/api/v1/lineage/documents/{id}/download` | owner or admin |
| `GET` | `/api/v1/lineage/admin/audit-logs` | **ROLE_ADMIN** |
| `POST` | `/api/v1/lineage/dev/token` | open, **non-production profiles only** |
| — | `/actuator/health`, `/info`, `/prometheus` | public |
| — | everything else under `/actuator` | **ROLE_ADMIN** |

`env` and `loggers` are off the actuator exposure list by default. `/env` returns resolved
configuration including secrets and `/loggers` accepts POST, so neither belongs on a path any
authenticated citizen can reach. Opt in per environment with `MANAGEMENT_ENDPOINTS_EXPOSURE`
where an operator-only network path exists.

## What's in the box

**Ingress.** Rate limited per authenticated caller, not per IP — the filter runs after
authentication for that reason. UUIDv7 transaction IDs. Idempotency keys scoped per user,
because they're client-supplied and only unique within one caller.

**Transactional outbox with log-based CDC.** `LineageQueryService` writes the task, an outbox
row and an audit row in one transaction, and stops. Debezium streams the outbox table from the
WAL and its `EventRouter` SMT puts it on `lineage.query.events` keyed by transaction ID, so
events for one transaction stay ordered on one partition. A domain write and a broker publish
can't be made atomic; this arrangement means there's only ever one write, and delivery is the
CDC pipeline's problem. A broker outage delays events. It cannot lose them.

**Three-phase worker pipeline.** Ancestry graph traversal, identity and certificate
verification, certified document generation with a verification seal. A Redis lock
(`lock:lineage:processing:{txId}`, fenced with a per-attempt token) keeps two pods off one task.
Retries go back around through the outbox and are counted on the task row; exhausted retries
record the real cause and a compliance audit entry, then rethrow to
`lineage.query.events.dlt`.

**TCKN encryption at rest.** A JPA attribute converter encrypts on the way to the database via
Vault Transit, falling back to local AES-256-GCM envelope encryption. Decryption fails loudly
rather than handing ciphertext back to a caller who thinks it's a national ID.

**Observability.** Micrometer with a Zipkin exporter, JSON logs carrying `traceId`, `spanId`,
`transactionId` and `userId` across the Kafka handoff and the async executor, and custom
counters (`lineage.queries.submitted`, `.completed`, `.failed`).

## Deployment and autoscaling

Helm chart in [`helm/pedigree-lineage`](helm/pedigree-lineage), ArgoCD manifests in
[`argocd/`](argocd), Flux in [`flux/`](flux). `scripts/validate_manifests.sh` renders and asserts
the chart.

KEDA scales workers on Kafka consumer-group lag for `lineage.query.events`, polled every 5
seconds, targeting 1000 lag per pod, between 2 and 12 replicas.

**12 is the partition count, not a preference.** Kafka gives each partition to at most one
consumer in a group, so pods past `kafka.topicPartitions` join, get no assignment, and idle while
burning their resource requests. Raising `maxReplicaCount` alone does nothing. Repartition the
topic first, then raise both.

Argo Rollouts runs 10% → 2m → analysis → 30% → 60% → 100%, failing on 5xx rate above 0.5%, worker
CPU above 90%, or any arrival on the dead-letter topic. Graceful shutdown is
`server.shutdown=graceful` with a 60s phase timeout against `terminationGracePeriodSeconds: 90`,
so in-flight work finishes and commits its offsets before the pod goes.

## Layout

```
com.edevlet.lineage
├── domain/          entities, enums, repositories, exceptions - depends on nothing
├── dto/             REST request/response shapes
├── service/         LineageQueryService - synchronous ingress use cases
├── infrastructure/  everything that talks outside the JVM
│   ├── client/        legacy census/graph backend (circuit breaker + fallback)
│   ├── messaging/     Kafka config, consumer, message shape
│   ├── pipeline/      orchestrator, phase runner, failure handler
│   ├── ratelimit/     per-caller rate limiting filter
│   ├── security/      JWT converter, SecurityConfig, config guard, encryption/
│   ├── tracing/       MDC filter and async task decorator
│   ├── util/          TCKN validation, UUIDv7
│   └── vault/         dynamic secret configuration
├── web/             controllers, global exception handler
└── config/          Async, Redis, Actuator metrics
```

Deliberately not an exhaustive file listing — those rot the moment someone adds a class, and
this one had done exactly that. `ls` is authoritative;
[`docs/CODE_MAP.md`](docs/CODE_MAP.md) explains what the packages are for.

## Documentation

| Doc | Read it when |
|---|---|
| [`docs/CODE_MAP.md`](docs/CODE_MAP.md) | Changing code. Package layout, one query traced end to end, transaction boundaries, threading, invariants that must not break. |
| [`docs/CONFIGURATION.md`](docs/CONFIGURATION.md) | Deploying or wiring an environment. Every property and env var, its default, what breaks when it's wrong. |
| [`docs/OPERATIONS.md`](docs/OPERATIONS.md) | Something is misbehaving. Bring-up, deployment, autoscaling behaviour, symptom-to-cause failure modes. |
| [`docs/TESTING.md`](docs/TESTING.md) | Writing or trusting a test. What each class proves, which ones can't see transaction bugs, and the setup traps. |
| [`docs/WHAT_WAS_BROKEN.md`](docs/WHAT_WAS_BROKEN.md) | **Before reverting anything** in security, pipeline transactions or autoscaling. Seven defects, root causes, what's still unverified. |
| [`docs/SECURITY_ARCHITECTURE_NOTES.md`](docs/SECURITY_ARCHITECTURE_NOTES.md) | TCKN encryption at rest, Vault dynamic secrets, SPIFFE/SPIRE mTLS. |
| [`docs/ARCHITECTURE_AND_CANARY_DEPLOYMENTS.md`](docs/ARCHITECTURE_AND_CANARY_DEPLOYMENTS.md) | KEDA consumer-lag autoscaling and progressive delivery in depth. |
| [`NOTES.md`](NOTES.md) | Deployment and scaling notes, Helm file mapping, utility commands. |
