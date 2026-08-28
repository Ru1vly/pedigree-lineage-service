# Configuration reference

Every knob that matters, what it defaults to, and what breaks when you get it wrong.

Defaults in `application.yml` are chosen so a fresh clone boots with nothing but Docker
Compose. That means several of them are deliberately insecure. `SecretsConfigurationGuard`
turns "forgot to override this before deploying" into a startup failure under the `production`
profile rather than a silent leak, and the list of what it checks is at the bottom of this
document.

## Security

| Property | Env var | Default | Notes |
|---|---|---|---|
| `app.security.jwt.jwk-set-uri` | `JWT_JWK_SET_URI` | empty | JWKS endpoint. Set this and validation is asymmetric. |
| `app.security.jwt.issuer-uri` | `JWT_ISSUER_URI` | empty | OIDC discovery. Also validated as the `iss` claim. |
| `app.security.jwt.audience` | `JWT_AUDIENCE` | empty | Expected `aud`. Blank means audience is not checked. |
| `app.security.jwt.secret` | `JWT_HMAC_SECRET` | insecure placeholder | Shared HS256 secret. **Development only.** |
| `app.security.encryption.master-key` | `TCKN_ENCRYPTION_MASTER_KEY` | insecure placeholder | Envelope-encryption key for the AES-256-GCM fallback. |
| `app.security.vault.enabled` | `VAULT_TRANSIT_ENABLED` | `true` | Whether TCKN encryption uses Vault Transit. |

`jwk-set-uri` wins over `issuer-uri` when both are set. With neither, the service validates
tokens with the shared HS256 secret, which is symmetric: the string that verifies is the string
that signs, `DevTokenController` mints with it, and anyone who reads it can forge a token for
any user and any role including ADMIN. That is fine on a laptop and unacceptable anywhere else,
which is why the guard refuses to start under `production` without a provider configured.

**Dead property, do not trust it:**
`spring.security.oauth2.resourceserver.jwt.issuer-uri` (env `SPRING_SECURITY_OAUTH2_ISSUER_URI`,
default `http://localhost:8081/realms/e-devlet`, fed by `configmap.yaml`) does nothing. A custom
`JwtDecoder` bean overrides Boot's auto-configuration entirely, so this property has never been
read. It is a good part of why the OIDC story used to look real to anyone reading the config.
Use the `app.security.jwt.*` keys above. Deleting it would be an improvement.

## Actuator

| Property | Env var | Default |
|---|---|---|
| `management.endpoints.web.exposure.include` | `MANAGEMENT_ENDPOINTS_EXPOSURE` | `health,info,metrics,prometheus` |

`env` and `loggers` are **not** exposed by default. `/env` returns resolved configuration —
signing secret, encryption master key, database and Vault credentials — and `/loggers` accepts
POST, so it mutates the running process. If you need them in a given environment, opt in with
the env var, and only where an operator-only network path exists.

The authorization rule in `SecurityConfig` (`/actuator/**` requires ROLE_ADMIN, with only
`health`, `health/**`, `info` and `prometheus` public) is the real control. The exposure list is
defence in depth. You need both; neither one justifies removing the other.

`/actuator/health` shows full details (`show-details: always`) and liveness/readiness probes are
enabled. Health is public, so keep an eye on what your health indicators put in the response
body — a detail block naming internal hosts is a small leak that is easy to add by accident.

## Kafka

| Property | Env var | Default |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` |
| `spring.kafka.consumer.group-id` | `SPRING_KAFKA_CONSUMER_GROUP_ID` | `lineage-worker-group` |
| `spring.kafka.listener.concurrency` | `SPRING_KAFKA_LISTENER_CONCURRENCY` | `3` |
| `spring.kafka.consumer.max-poll-records` | `SPRING_KAFKA_CONSUMER_MAX_POLL_RECORDS` | Spring default; Helm sets `20` |
| `app.kafka.listener.auto-startup` | `APP_KAFKA_LISTENER_AUTO_STARTUP` | `true` |

Topics are constants in `KafkaConfig`, not properties: `lineage.query.events` and
`lineage.query.events.dlt`. If you rename either, the KEDA trigger (`keda.topic`), the canary
analysis query and `kafka.dltTopic` in `values.yaml` all have to move with it.

`auto-startup` is set to `false` only in the `test` profile, to stop the listener container
spawning a background consumer that retries against a broker no test ever started. It is not a
production switch.

`consumer.auto-offset-reset` is `earliest`. A brand-new consumer group will replay the topic
from the beginning. That is usually what you want on first deploy and occasionally a nasty
surprise if you rename the group in an environment with retained history.

## Database and migrations

| Property | Env var | Default |
|---|---|---|
| `spring.datasource.url` | `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/lineagedb` |
| `spring.datasource.username` | `SPRING_DATASOURCE_USERNAME` | `lineageuser` |
| `spring.datasource.password` | `SPRING_DATASOURCE_PASSWORD` | `lineagepass` |

Hikari: pool 20, min idle 5, idle timeout 5m, max lifetime 30m, connection timeout 30s. The
pipeline holds a connection for the duration of `runPhases`, which includes the census backend
call, so pool size and worker concurrency are related. Replicas × listener concurrency should
stay comfortably under pool size × replicas — in practice concurrency 3 against a pool of 20
leaves plenty of headroom, but if you raise concurrency, check this before you find out the
hard way.

`ddl-auto: validate`. Schema changes go through Flyway (`V1`–`V4` in
`src/main/resources/db/migration`), never through Hibernate. Debezium reads the WAL, so
migrations that rewrite `transactional_outbox` need thinking about, not just applying.

## Redis

| Property | Env var | Default |
|---|---|---|
| `spring.data.redis.host` | `SPRING_REDIS_HOST` | `localhost` |
| `spring.data.redis.port` | `SPRING_REDIS_PORT` | `6379` |

Two uses, both with fixed key prefixes in code:

- `lock:lineage:processing:{txId}` — the distributed processing lock, 10 minute TTL, released
  by a Lua compare-and-delete against a per-attempt fencing token.
- `state:lineage:{txId}` — cached task state for fast status polling, 1 hour TTL.

Redis being down degrades differently in each case. A failed state-cache write is caught and
logged; polling falls back to the database. A failed lock acquisition throws, and the task is
handled as a pipeline failure — retried, and eventually dead-lettered. That is intentional:
running the pipeline without the lock risks two workers on one task.

## Vault

| Property | Env var | Default |
|---|---|---|
| `spring.cloud.vault.enabled` | `SPRING_CLOUD_VAULT_ENABLED` | `true` |
| `spring.cloud.vault.host` | `SPRING_CLOUD_VAULT_HOST` | `localhost` |
| `spring.cloud.vault.port` | `SPRING_CLOUD_VAULT_PORT` | `8200` |
| `spring.cloud.vault.scheme` | `SPRING_CLOUD_VAULT_SCHEME` | `http` |
| `spring.cloud.vault.token` | `SPRING_CLOUD_VAULT_TOKEN` | `root` |

Dynamic database credentials on a 1 hour lease with renewal, role `pedigree-db-role`.
`fail-fast: false`, so the application starts even when Vault is unreachable and falls back to
envelope encryption for TCKN — but note that `decrypt` will now *throw* on ciphertext it cannot
handle rather than quietly returning it. Loud failure during an outage is the intended
behaviour.

The Helm chart projects the connection details as non-secret config and the token from a Secret.
Without those, a pod falls back to `localhost:8200`, which does not exist inside a container.

## Resilience

Rate limiting: 10 requests per minute per client key, 100ms timeout, declared under
`resilience4j.ratelimiter.configs.lineageIngress`. It must stay under `configs`, not
`instances` — `RateLimitingFilter` builds one limiter per caller from that template via
`registry.rateLimiter(clientKey, "lineageIngress")`, and `instances` would register a single
eager limiter and no retrievable config.

Circuit breaker `legacyCensusBackend`: count-based window of 10, minimum 5 calls, 50% failure
threshold, 10s open, 3 half-open trial calls, with a fallback method on the client.

## Application behaviour

| Property | Env var | Default | Notes |
|---|---|---|---|
| `app.pipeline.phase-delay-ms` | `APP_PIPELINE_PHASE_DELAY_MS` | `1500` | Simulated legacy-backend latency. Test profile uses 100. |

## Helm values worth knowing

`kafka.topicPartitions` (default 12) is the ceiling on useful worker replicas, and
`keda.maxReplicaCount` should never exceed it. Kafka assigns each partition to at most one
consumer in a group; extra pods join, get nothing, and idle while consuming their resource
requests. If you need more parallelism, repartition the topic first and raise both together.
**Check the default against your actual topic** — 12 is a documented guess, not a reading of
your cluster.

`keda.auth.enabled` is off by default. An in-cluster PLAINTEXT listener needs no
`TriggerAuthentication`, and rendering an empty one makes the KEDA scaler fail rather than fall
back to anonymous access.

Every credential in `values.yaml` is an obvious placeholder so `helm template` works out of the
box. For real deployments set the matching `existingSecret` field and point at a Secret your
platform already manages (Vault Agent Injector, ExternalSecrets, Sealed Secrets), which makes
the chart reference it instead of templating its own.

## What the production guard enforces

Under the `production` profile, `SecretsConfigurationGuard` fails startup if:

- neither `app.security.jwt.jwk-set-uri` nor `app.security.jwt.issuer-uri` is set — no external
  identity provider means symmetric HS256 validation, and that is not a production auth model;
- `app.security.jwt.secret` is still the repo default;
- `app.security.encryption.master-key` is still the repo default;
- `spring.cloud.vault.token` is still `root` and Vault config is enabled.

Outside `production` each of these logs a warning instead. Read your startup logs on a fresh
environment; the warnings are there to be noticed, not scrolled past.
