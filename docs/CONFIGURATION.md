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
| `app.security.encryption.master-key` | `TCKN_ENCRYPTION_MASTER_KEY` | insecure placeholder | Secret for the local AES-256-GCM key, and the only key that reads legacy `enc:v1:gcm:` rows. **No hardcoded fallback** — absent, startup fails. |
| `app.security.encryption.active-key-id` | `TCKN_ENCRYPTION_ACTIVE_KEY_ID` | `primary` | Key id new ciphertexts are written under. Must exist in the keyring. |
| `app.security.encryption.keys.<id>` | — | empty | Keyring for rotation: every listed key can decrypt, `active-key-id` encrypts. |
| `app.security.encryption.kdf.salt` | `TCKN_ENCRYPTION_KDF_SALT` | shipped placeholder | PBKDF2 salt. Not secret; must be per-environment and **must never change once rows exist**. |
| `app.security.encryption.kdf.iterations` | `TCKN_ENCRYPTION_KDF_ITERATIONS` | `210000` | Clamped up to 100 000. Paid once per key at startup, not per row. |
| `app.security.vault.enabled` | `VAULT_TRANSIT_ENABLED` | `true` | Whether TCKN encryption uses Vault Transit. |
| `app.security.trust-forwarded-headers` | `APP_SECURITY_TRUST_FORWARDED_HEADERS` | `false` | Believe `X-Forwarded-For`/`X-Real-IP` when recording a caller's origin. |

**`trust-forwarded-headers` is a compliance setting, and both values are wrong somewhere.**
`ClientOriginEnrichmentFilter` records the caller's IP and user agent onto the identity context,
which is what `lineage_audit_logs.ip_address` is written from — on the synchronous path and, via
the outbox message, on the worker too. Left `false`, the origin is `getRemoteAddr()`. Behind the
nginx ingress in `helm/`, that is the ingress pod's address and the citizen's real IP appears only
in `X-Forwarded-For`, so **that deployment must set this true**. On a directly exposed deployment
it must stay false: the header is client-supplied, and trusting it lets a caller choose what the
audit trail records about them. When no origin can be determined the filter records
`UNKNOWN_ORIGIN` rather than inventing a plausible one.

`jwk-set-uri` wins over `issuer-uri` when both are set. With neither, the service validates
tokens with the shared HS256 secret, which is symmetric: the string that verifies is the string
that signs, `DevTokenController` mints with it, and anyone who reads it can forge a token for
any user and any role including ADMIN. That is fine on a laptop and unacceptable anywhere else,
which is why the guard refuses to start under `production` without a provider configured.

**A property that used to be here and is now gone:**
`spring.security.oauth2.resourceserver.jwt.issuer-uri` (env `SPRING_SECURITY_OAUTH2_ISSUER_URI`)
did nothing. `SecurityConfig` declares its own `JwtDecoder` bean, so Boot's resource-server
auto-configuration backs off (`@ConditionalOnMissingBean`) and never reads that prefix — while
`application.yml` set it to a Keycloak realm and `configmap.yaml` fed it from
`security.oauth2.issuerUri`. Anyone reading the config, or the cluster's ConfigMap, saw a service
apparently validating tokens against an identity provider; it was validating them with the shared
HS256 secret. Both are removed, and the chart now renders the same value into `JWT_ISSUER_URI`
(`app.security.jwt.issuer-uri`), which `SecurityConfig` does read — so the setting that looked
like it worked now actually does.

### Rotating the TCKN encryption key

Ciphertext written by the current format carries its key id — `enc:v2:<keyId>:<payload>` — which
is what makes this possible at all. The previous format named no key, so changing
`TCKN_ENCRYPTION_MASTER_KEY` did not rotate anything: it orphaned every historical row, and every
later read of one threw.

1. Add the new secret under a **new** id: `app.security.encryption.keys.2026-q3`. Keep the
   existing entry. Deploy. Nothing changes yet — the new key is only decryptable, not active.
2. Point `active-key-id` at the new id and deploy. New writes use it; old rows still read under
   the old key.
3. Rewrite the old rows (load and save each affected entity so the attribute converter
   re-encrypts it under the active key). Rows still in `enc:v1:gcm:` or in pre-encryption
   plaintext migrate on the same pass.
4. Only once nothing references the old id, remove its entry. A ciphertext naming a key that is
   no longer configured fails loudly and names the missing id — it is not silently unreadable,
   but it is unreadable.

`kdf.salt` is **not** rotatable this way. It is an input to every key's derivation, so changing
it has exactly the effect that changing the old master key had. Choose it once per environment.

Two practical notes on the keyring itself. There is no single environment variable for a map, so
`keys.<id>` is set through a values file, `SPRING_APPLICATION_JSON`, or
`--set-string`/`-Dapp.security.encryption.keys.<id>=...`, with the secret itself coming from
wherever your platform keeps secrets. And **use lowercase key ids** (`2026-q3`, not `2026-Q3`):
Spring's relaxed binding canonicalises property-name segments, so an uppercase id in a properties
file arrives lowercased, and the id is what gets written into every ciphertext. Ids are restricted
to `[A-Za-z0-9_-]` and at most 32 characters, because they live inside the ciphertext where `:` is
the field separator.

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

`ddl-auto: validate`. Schema changes go through Flyway (`V1`–`V5` in
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
- `state:lineage:{txId}` — cached task state for fast status polling, 1 hour TTL. Written after
  each phase transaction commits (`LineageTaskStateCache`) and read by
  `LineageQueryService.getQueryStatus` for non-terminal tasks; terminal reads and cache misses go
  to Postgres. Invalidated on cancel, on retry re-queue, and on dead-lettering.
- `ratelimit:lineage:ingress:{clientKey}` — per-user ingress counter, expires with the rate limit
  window. See "Resilience" below.

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
local AES-256-GCM for TCKN — but note that `decrypt` will *throw* on ciphertext it cannot handle
rather than quietly returning it. Loud failure during an outage is the intended behaviour. The
one value that still passes through unchanged is a bare 11-digit TCKN, which is the single shape
a pre-encryption row can legitimately have; it is logged at WARN and rewritten on next save.

The Helm chart projects the connection details as non-secret config and the token from a Secret.
Without those, a pod falls back to `localhost:8200`, which does not exist inside a container.

## Resilience

Rate limiting: 10 requests per minute per client key, configured under
`app.ratelimit.lineage-ingress` (`limit-for-period`, `refresh-period`), overridable per
environment with `APP_RATELIMIT_LIMIT_FOR_PERIOD` / `APP_RATELIMIT_REFRESH_PERIOD`.

The counter lives in **Redis**, not in the JVM. `RateLimitingFilter` runs an atomic
INCR/PEXPIRE Lua script against `ratelimit:lineage:ingress:{clientKey}`, so the configured
limit is the limit for the whole deployment. It was previously an in-JVM Resilience4j
`RateLimiterRegistry`, which gave every replica its own private allowance — the advertised
10/min was really 10×N, somewhere between 20 and 120 across the 2–12 replicas KEDA scales the
service to, depending on the current replica count and which pod the load balancer picked. The
registry also keyed limiters by userId and never evicted them; the Redis keys expire with the
window.

If Redis is unreachable the filter **fails open** and logs at WARN. Rate limiting here protects
the census backend from overload and is not an authorization control, so a counter-store outage
must not become a service outage.

Circuit breaker `legacyCensusBackend`: count-based window of 10, minimum 5 calls, 50% failure
threshold, 10s open, 3 half-open trial calls, and **no fallback method**. An open circuit
propagates so the pipeline fails the task with the real cause; a fallback here previously
substituted invented ancestry that the pipeline then certified as a completed document.

### The retry budget, multiplied out

There are two retry layers around the census backend and they compose. For a long time only one
of them was configured and nobody had multiplied them together.

| Layer | Where | Setting |
|---|---|---|
| Census call | `@Retry` on `LegacyCensusGraphClientImpl` | `resilience4j.retry.instances.legacyCensusBackend.maxAttempts` (2), 500ms exponential |
| Pipeline attempt | `PipelineFailureHandler` re-queue via the outbox | `app.pipeline.retry.max-retries` (2), 2s × 3 backoff capped at 10s |

Worst case is therefore `(1 + max-retries) × maxAttempts` = **6 calls per task**, spread over
seconds. It was previously twelve, essentially back-to-back: `@Retry` was named in the code but
had no `resilience4j.retry` block, so it silently ran on the library default of 3 attempts and
retried *everything* — including the circuit breaker's own `CallNotPermittedException`, the one
exception where retrying is guaranteed to be useless. The pipeline layer then re-queued with no
delay at all, because an outbox row is on Kafka within milliseconds of the commit. Both layers
are now explicit, `CallNotPermittedException` is ignored by the retry, and a re-queued attempt
carries a not-before instant that `LineageTaskConsumer` waits for.

That wait is taken on a Kafka listener thread — there is nowhere else to take it, since the
outbox publishes as soon as its transaction commits — and is capped by
`app.pipeline.retry.max-consumer-deferral` (15s). A delay topic is the right answer if the budget
ever needs to stretch past seconds.

### Live progress streams

| Property | Env var | Default | Notes |
|---|---|---|---|
| `app.sse.max-concurrent-streams` | `APP_SSE_MAX_CONCURRENT_STREAMS` | `200` | Per instance. Beyond it, `GET .../stream` answers 503 with `Retry-After`. |
| `app.sse.scheduler-pool-size` | `APP_SSE_SCHEDULER_POOL_SIZE` | `8` | Threads shared by every open stream's poll. |
| `app.sse.poll-interval` | `APP_SSE_POLL_INTERVAL` | `2s` | |

The stream is a poll published as SSE, not a push, and the polls share one fixed-size scheduler.
The pool was hard-coded at 4 with no limit on the number of streams feeding it, which does not
fail loudly — the queue simply grows and the advertised interval stretches for everyone connected
while the endpoint carries on promising two seconds. Refusing the connection that crosses the
ceiling makes the limit visible instead. Watch the `lineage_sse_streams_active` gauge against
`max-concurrent-streams`; sustained saturation means scale out, not a bigger number.

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
- `app.security.jwt.secret` is missing, or still the repo default;
- the encryption secret actually in use — `keys.<active-key-id>` when the keyring is configured,
  `master-key` otherwise — is missing, or still the repo default;
- `app.security.encryption.kdf.salt` is still the shipped default;
- `spring.cloud.vault.token` is still `root` and Vault config is enabled.

**"Missing" counts, and that is the point.** The guard used to return early unless a value
*equalled* a known insecure default, so a blank or absent one passed silently — while
`VaultTransitTcknEncryptionService` carried its own hardcoded fallback secret, a different
literal from the one the guard knew about. Deleting a single line from `application.yml` therefore
produced a production deployment that started cleanly and encrypted every citizen's TCKN under a
key committed to this repository. Both halves are closed: absent is treated exactly like
insecure, and the encryption service has no fallback secret at all — with no key configured it
refuses to start.

Note also that it checks the key that *encrypts*, not whichever property is readable. With a
keyring configured, a real `master-key` alongside a defaulted `keys.<active-key-id>` is still a
failure.

Outside `production` each of these logs a warning instead. Read your startup logs on a fresh
environment; the warnings are there to be noticed, not scrolled past.
