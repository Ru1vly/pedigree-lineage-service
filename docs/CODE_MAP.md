# Code map

Where things live, what happens in what order, and which invariants you are not allowed to
break. If you are about to change something in `infrastructure/pipeline` or
`infrastructure/security`, read the relevant section first — several of the odd-looking
decisions in there are load-bearing and there is a defect writeup
([`WHAT_WAS_BROKEN.md`](WHAT_WAS_BROKEN.md)) explaining what happened last time they weren't.

## Layout

```
domain/          entities, enums, repositories, exceptions. No Spring web, no infrastructure.
dto/             request/response shapes for the REST layer. Not persisted, not published.
service/         LineageQueryService - the synchronous ingress use cases.
infrastructure/  everything that talks to something outside the JVM.
  client/        LegacyCensusGraphClient (+ Impl) - the census/graph backend.
  messaging/     Kafka config, consumer, message shape.
  pipeline/      the async worker: orchestrator, phase runner, failure handler.
  ratelimit/     RateLimitingFilter.
  security/      JWT converter, SecurityConfig, identity holder, config guard.
    encryption/  TCKN field-level encryption + the JPA attribute converter.
  tracing/       MDC filter and the async task decorator.
  util/          TCKN validation, UUIDv7.
  vault/         dynamic secret configuration.
web/             controllers and the global exception handler.
config/          Async, Redis, Actuator metrics, app-wide beans.
```

The dependency direction is inward: `web` and `infrastructure` may know about `domain`;
`domain` knows about nobody. That is the only architectural rule here that is worth being
strict about, and it currently holds.

## The lifecycle of one lineage query

Follow it end to end once and most of the codebase explains itself.

**1. Ingress.** `POST /api/v1/lineage/queries`. The filter chain authenticates the bearer
token first, then `RateLimitingFilter` runs — in that order, deliberately, so limits key on
the authenticated user rather than on an IP shared by half of Ankara. It is registered via
`addFilterAfter(..., BearerTokenAuthenticationFilter.class)` and its Boot auto-registration is
explicitly disabled in `SecurityConfig`, otherwise it would *also* run early and
unauthenticated as a plain servlet filter. Both halves of that are needed. Delete the
`FilterRegistrationBean` and you get a second, useless, pre-auth invocation.

**2. `LineageQueryService.submitQuery`**, one transaction, does four things: checks
idempotency scoped to the caller, saves the `LineageQueryTask`, saves an `OutboxEvent`, saves
an audit row. `idempotencyKey` is client-supplied and only unique per user — see
`V3__scope_idempotency_key_to_user.sql` — so the uniqueness constraint is on the pair. Two
different citizens sending the same key is normal, not a collision.

Note what this method does *not* do: publish to Kafka. Nothing in this codebase publishes to
Kafka. That's the point of the next step.

**3. Debezium.** The outbox row hits the PostgreSQL WAL, Debezium streams it, and the
`EventRouter` SMT (`debezium/lineage-outbox-connector.json`) routes it onto
`lineage.query.events`, keyed by `aggregate_id` — the transaction ID — so all events for one
transaction land on one partition and stay ordered.

The reason it's built this way: a domain write and a broker publish cannot be made atomic. Do
them separately and you eventually get a task with no message, or a message with no task. Here
the only write is the outbox INSERT, in the same transaction as the domain write, and delivery
is the CDC pipeline's problem. A broker outage delays events; it cannot lose or duplicate the
decision to send one.

**4. `LineageTaskConsumer`** picks it up, restores the MDC (trace, transaction, user) and
rebuilds the `NationalIdentityContext` onto the worker thread. That context is not decoration —
the audit rows written later in the pipeline read the identity from it rather than from the
message. If the propagation breaks, auditing degrades to a warning log and the audit row is
silently skipped.

**5. `LineagePipelineOrchestrator.executePipeline`** takes a Redis lock
(`lock:lineage:processing:{txId}`, 10 minute TTL) with a per-attempt fencing token, runs the
pipeline, and releases the lock with a Lua compare-and-delete that only deletes if the token
still matches. The Lua matters: a worker that overruns the TTL would otherwise delete a
*second* worker's lock on its way out, and you'd have two workers on one task believing they
were each alone.

**6. `LineagePipelinePhaseRunner`** holds the transactions — one per phase, not one for the
whole run. `beginProcessing` reloads the task and skips it if already terminal (10%), then
`verifyIdentityRecords` (35%), `generateDocuments` (70%) and `completeWithAncestry` (100%) each
commit on their own, sequenced by the orchestrator.

That split is what makes the intermediate progress real. Phases 2 and 3 previously shared a
transaction with the completion write, so 35 and 70 were overwritten by 100 before anything was
committed and no poller could observe either — the SSE stream could only ever emit 0 → 10 → 100.

Each committed transition is mirrored into Redis via `LineageTaskStateCache`
(`state:lineage:{txId}`, 1 hour TTL) **after commit**, so the cache can never advertise progress
a rollback erased. `LineageQueryService.getQueryStatus` reads that cache for non-terminal tasks —
the hot polling path — and falls through to Postgres on a miss or for a terminal task, which
needs the ancestry result the cache deliberately does not carry. The ownership check runs on both
paths. On success the runner writes a `LINEAGE_QUERY_COMPLETED` audit row.

**7. Failure** unwinds to the orchestrator, outside any transaction, which calls
`PipelineFailureHandler.recordFailureAndMaybeRetry`. Under `maxRetries` it flips the task back
to SUBMITTED and writes a *new outbox row* — the retry goes back around through Debezium and
Kafka like any other event, committed atomically with the status change. Over `maxRetries` it
records FAILED with the real cause plus a `LINEAGE_QUERY_FAILED` audit row, and the
orchestrator rethrows so Kafka routes the record to `lineage.query.events.dlt`.

**8. The DLT consumer** calls `finalizeFailure`, which is a backstop and knows it. It only
stamps `MAX_RETRIES_EXCEEDED_DLQ` when nothing more specific was recorded.

## Transaction boundaries

This is the part that has already been got wrong once, so it gets its own section.

| Where | Transaction |
|---|---|
| `LineageQueryService.submitQuery` / `cancelQuery` | `@Transactional` — task + outbox + audit are one unit |
| `LineageQueryService.getQueryStatus` | `@Transactional(readOnly = true)` |
| `LineagePipelineOrchestrator.executePipeline` | **none, deliberately** |
| `LineagePipelinePhaseRunner.runPhases` | `@Transactional` — all pipeline DB work |
| `PipelineFailureHandler.recordFailureAndMaybeRetry` | `@Transactional` — runs *after* the above rolled back |
| `LineagePipelineOrchestrator.finalizeFailure` | `@Transactional` — called from the DLT consumer |

**The invariant: the orchestrator must never become transactional again.** It rethrows to reach
the dead-letter topic. Anything written in a transaction that the rethrow rolls back is gone —
which is exactly how the terminal FAILED status and its compliance audit row used to vanish,
leaving a generic DLQ code and no audit trail at all.

And no, you cannot patch that with `@Transactional(propagation = REQUIRES_NEW)` on the failure
handler. That was tried. By then the phase transaction has flushed its UPDATE and holds a row
lock on `lineage_queries`; `REQUIRES_NEW` *suspends* it while it's still holding that lock and
opens a second transaction that needs the same row. They wait on each other until the database
gives up:

```
Timeout trying to lock table "LINEAGE_QUERIES"
```

Three beans instead of one is the price of getting this right. Pay it.

## Threading

Four distinct pools, all bounded, none created per request:

- **Tomcat request threads** — the REST layer. `server.shutdown: graceful` plus
  `spring.lifecycle.timeout-per-shutdown-phase: 60s`.
- **Kafka listener** — `spring.kafka.listener.concurrency`, default 3 per pod. Total consumers
  across the deployment is replicas × concurrency, capped by partition count. Past that they
  idle.
- **`async-lineage-*`** — `AsyncConfig.getAsyncExecutor()`, core 5 / max 20 / queue 100, with
  `MdcTaskDecorator` so trace context survives the handoff.
- **`sse-progress-*`** — one shared `ThreadPoolTaskScheduler`, pool size 4,
  `removeOnCancelPolicy` on, shut down with the context.

The SSE scheduler exists because the streaming endpoint used to call
`Executors.newSingleThreadExecutor()` per request and never shut it down — an unbounded thread
allocation on a path whose call rate is chosen by clients. If you add another async path, use
an existing pool or add a managed bean. Do not allocate an executor inside a request method.

The SSE scheduler has no `MdcTaskDecorator`, and that is not an oversight:
`ThreadPoolTaskScheduler.setTaskDecorator` landed in Spring Framework 6.2 and this is Boot
3.3.2 / Spring 6.1.11. There's a comment at the call site.

## Security

`SecurityConfig` builds the chain. Matcher order is significant and the actuator rule is the
one to be careful with:

```
/actuator/health, /health/**, /info, /prometheus   permitAll   (probes and scrape)
/actuator/**                                       ROLE_ADMIN  (env, loggers, everything else)
/api/v1/lineage/dev/**                             permitAll   (non-prod controller only)
/api/v1/lineage/admin/**                           ROLE_ADMIN
everything else                                    authenticated
```

Drop the `/actuator/**` line and `env` and `loggers` fall through to `authenticated()`, which
means any citizen token can dump the resolved configuration — signing secret, encryption key,
database and Vault credentials — and POST new log levels. `env` and `loggers` are also off the
default exposure list; that is the second layer, not a replacement for the first.

`jwtDecoder()` prefers `app.security.jwt.jwk-set-uri` or `issuer-uri` and validates
asymmetrically against the provider's published keys, checking `iss` and (when configured)
`aud`. Without either, it falls back to the shared HS256 secret, which is a development mode:
the same string signs and verifies, `DevTokenController` mints with it, and whoever holds it
can forge any role. `SecretsConfigurationGuard` refuses to start under the `production` profile
in that state.

`CustomJwtAuthenticationConverter` rejects a token with no usable national identity claim and
runs the claim through `TcknValidator`. It used to substitute `10000000000` — a value the
project's own validator classifies as invalid — which attributed real queries and audit rows to
an identity belonging to nobody.

## Encryption at rest

`TcknAttributeConverter` sits on the JPA attribute, so encryption happens on the way to the
database and nowhere else in the code has to remember. `VaultTransitTcknEncryptionService`
prefers Vault Transit (`vault:v1:` prefix) and falls back to local AES-256-GCM envelope
encryption (`enc:v1:gcm:`) when Vault is off or unreachable.

`decrypt` throws rather than returning its argument. It used to swallow the failure and return
the ciphertext, so during a Vault outage callers received an encrypted blob and treated it as a
citizen's national ID — storing, logging and comparing it as one, with no exception anywhere.
Every terminal path now either returns real plaintext or throws.

## Resilience

`LegacyCensusGraphClientImpl` is behind a Resilience4j circuit breaker
(`legacyCensusBackend`, 10-call window, 50% failure threshold, 10s open) with a fallback, plus
`@Retry`. Ingress is rate limited to 10 requests per minute per client key.

That limit is counted in Redis (`ratelimit:lineage:ingress:{clientKey}`, atomic INCR/PEXPIRE),
not in the JVM, so it does not multiply by the replica count. See `docs/CONFIGURATION.md` for
the configuration keys and the fail-open behaviour when Redis is down.

Kafka-level retry is `FixedBackOff(0, 0)`: straight to the DLT on first failure. That is
correct here and not a missing configuration. Retries are the application's job, via the outbox
re-queue in `PipelineFailureHandler`, because those retries need to be transactional and
counted against `maxRetries` on the task row. Broker-level redelivery would double up with
that and lose the count.
