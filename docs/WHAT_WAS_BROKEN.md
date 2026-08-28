# What was broken and why

Seven defects, what caused them, and what changed. Read this before you "simplify" any of
it back to how it was.

Two of these were security holes that composed into full privilege escalation. One erased
the compliance audit trail this service exists to keep. One pointed the entire autoscaling
story at a message broker that isn't in the stack any more. The rest were smaller but real.

None of them were found by the test suite. That is its own problem and it gets a section at
the end.

---

## 1. The compliance audit trail was deleted by the code that wrote it

`LineagePipelineOrchestrator.executePipeline` was `@Transactional`. When retries ran out it
wrote a FAILED status with the real error, wrote a `LINEAGE_QUERY_FAILED` audit row, and
then rethrew so Kafka would route the record to the dead-letter topic.

Rethrowing out of a `@Transactional` method rolls the transaction back. Both writes went
with it. Every time.

The task still ended up FAILED, which is why nobody noticed. The DLT consumer picked the
record up and called `finalizeFailure`, which stamped it `MAX_RETRIES_EXCEEDED_DLQ`. So the
database ended up with a row that says "this failed because it was dead-lettered", which
tells you nothing, and no audit record at all. For a service whose admin controller is
labelled a compliance audit trail, losing the audit row on exactly the path that most needs
one is not a rough edge. That is the feature failing.

### The obvious fix is wrong

`REQUIRES_NEW` on a separate bean. That's the textbook answer, it's what I wrote first, and
it deadlocks.

Here is why. By the time you reach the failure handler, the outer transaction has already
called `updateTaskPhase`, which does `queryRepository.save(task)`. The subsequent
`findByTransactionId` query triggers a Hibernate auto-flush, so that UPDATE has gone to the
database and the outer transaction is holding a row lock on `lineage_queries`.
`REQUIRES_NEW` then *suspends* that transaction — suspended, not committed, not rolled back,
still holding the lock — and opens a second one that needs to UPDATE the same row. The
second waits on the first. The first is blocked waiting for the second to return. H2 said it
plainly:

```
Timeout trying to lock table "LINEAGE_QUERIES"
```

Postgres will do the same thing, just with different wording.

You cannot fix this with a propagation setting. The propagation setting was never the
problem. The transaction *boundary* was in the wrong place, and no amount of annotation
tuning moves a boundary.

### What actually changed

The boundary moved. `executePipeline` is not transactional at all now. It holds the Redis
lock, delegates, and decides retry-versus-dead-letter. That's orchestration; it has no
business owning a database transaction.

- `LineagePipelinePhaseRunner` — `@Transactional`, owns all the database work for one task.
  Its transaction ends when the method returns.
- `PipelineFailureHandler` — records the outcome. Called from outside any transaction, after
  the phase transaction has already rolled back and dropped its locks. Plain `REQUIRED` opens
  a fresh one and commits. Deliberately *not* `REQUIRES_NEW`, which would reintroduce the
  deadlock the moment anyone calls it from inside a transaction again.

`finalizeFailure` also stopped clobbering. It only knows the record hit the DLT, which is a
useless diagnosis, so it now fills in the error code only when the task isn't already FAILED
with a cause recorded. A generic backstop must never overwrite a specific answer.

One consequence to know about: the retry counter increment on the *terminal* attempt used to
be written by the doomed transaction and is now written by the failure handler. Same value,
different transaction. The retry path still commits the status change and the outbox row
together, which is the property that path actually needs.

---

## 2. Any citizen token could read every secret in the process

`SecurityConfig` permitted `/actuator/health`, `/info` and `/prometheus`, gated
`/api/v1/lineage/admin/**` behind ADMIN, and let everything else fall through to
`anyRequest().authenticated()`.

"Everything else" includes `/actuator/env` and `/actuator/loggers`, both of which were on the
`management.endpoints.web.exposure.include` list.

So any valid low-privilege token — the kind the service hands out to ordinary citizens —
could GET `/actuator/env` and receive the resolved configuration: the JWT signing secret, the
TCKN encryption master key, the database credentials, the Vault token. `/actuator/loggers` is
worse than it sounds, because it takes POST. That's not an information leak, that's runtime
mutation of the process by an unprivileged user.

Fixed in two places, because one is not enough:

- `SecurityConfig` now has `.requestMatchers("/actuator/**").hasRole("ADMIN")` sitting
  directly below the public-probe matcher and above `anyRequest()`. Matcher order is
  load-bearing here. Move that line and you reopen the hole.
- `env` and `loggers` came off the default exposure list. `MANAGEMENT_ENDPOINTS_EXPOSURE`
  opts back in per environment, for the environments that have an operator-only network path.

The authorization rule is the control. The exposure list is the second layer. Anyone who
"tidies up" by deleting one because the other exists has misunderstood both.

---

## 3. The OAuth2/OIDC story was set dressing

The architecture diagram claimed an external OIDC identity provider. `SecurityConfig.jwtDecoder()`
called `NimbusJwtDecoder.withSecretKey` with a static HS256 secret from
`app.security.jwt.secret`. There was no JWKS validation anywhere in the tree. Not misconfigured
— absent.

HS256 is symmetric. The string that verifies a token is the string that signs it. The service
held both halves, and `DevTokenController` minted tokens with the same secret. Anyone holding
that one string can forge a token for any user with any role, including ADMIN.

Now combine with defect 2 and you have the whole chain: a citizen token reads `/actuator/env`,
takes `app.security.jwt.secret` out of the dump, and signs itself an ADMIN token. Two findings
that each look bad on their own multiply into complete authentication bypass. This is why you
fix both, and why nobody gets to argue that "the env endpoint is only readable by authenticated
users" made it acceptable.

`jwtDecoder()` now prefers a real provider. Set `app.security.jwt.jwk-set-uri` (or
`issuer-uri` for discovery) and validation is asymmetric against the IdP's published keys —
this service holds public keys and cannot mint anything. `iss` is validated, and `aud` too when
`app.security.jwt.audience` is set, so a token minted for some other relying party is rejected
here instead of being accepted as a local identity.

The HS256 path still exists for local development, because requiring a Keycloak to run
`mvn test` would be its own kind of stupid. `SecretsConfigurationGuard` refuses to start under
the `production` profile with no provider configured. Development convenience is fine. It just
doesn't get to board the plane.

**Still misleading:** `application.yml` sets
`spring.security.oauth2.resourceserver.jwt.issuer-uri`, and `configmap.yaml` feeds it from
Helm. That property is dead — a custom `JwtDecoder` bean overrides Boot's auto-configuration
entirely, so it has never done anything. It's a decent part of why the OIDC story looked real
to a reader. I left it alone rather than wiring it up, because its default points at a
localhost Keycloak and every dev startup would begin attempting OIDC discovery against a
server that isn't running. Delete it or repoint it, but don't leave it there believing it
does something.

---

## 4. Tokens with no national ID got issued a fake one

```java
if (nationalId == null) {
    // Fallback for demo or test environments
    nationalId = "10000000000";
}
```

The comment says "demo or test". The code is in the production authentication path.

This identity is not decoration. It keys ownership checks, audit log rows, and the encrypted
TCKN column. Substituting a placeholder means real lineage queries get attributed to an
identity that belongs to nobody, and the audit trail records that fiction as fact. If two
different malformed tokens show up, their queries are now attributed to the *same* nonexistent
citizen.

The punchline is that `10000000000` doesn't pass this service's own `TcknValidator`. It's
listed in `TcknValidatorTest` as an invalid checksum. The code was fabricating a national
identity number the codebase already knows is impossible.

A token with no usable identity claim is now rejected with `InvalidBearerTokenException`, and
the claim is run through `TcknValidator.isValid` while we're there. If the IdP can't say who
the caller is, the answer is 401. It is not "make something up and carry on".

---

## 5. Vault decrypt could hand back the ciphertext and call it a national ID

`VaultTransitTcknEncryptionService.decrypt` tried Transit, fell back to base64-decoding the
wrapped payload, and if that threw:

```java
} catch (Exception ignored) {
}
```

then fell off the end of the method to `return cipherText`.

So during a Vault outage, `decrypt()` returned its own argument. Callers got an encrypted blob
and had no way to know — no exception, no flag, nothing. They then stored it, logged it,
compared it against real TCKNs, and masked it for display. Silent data corruption in the field
the entire encryption-at-rest design exists to protect.

`catch (Exception ignored) {}` followed by a fall-through return is how you build a system
that lies to itself. Every terminal path in `decrypt` now either returns something genuinely
decrypted or throws `IllegalStateException`. The final fall-through is unreachable while
`isEncrypted()` recognises exactly two prefixes, and it throws anyway, so that a third envelope
format added later cannot quietly degrade into passthrough.

Failing loudly during a Vault outage is correct. An outage is a real event and the caller needs
to know. Returning ciphertext dressed as plaintext turns a five-minute incident into corrupted
rows nobody finds for a year.

---

## 6. The SSE endpoint leaked a thread per request and wasn't streaming

```java
Executors.newSingleThreadExecutor().execute(() -> { ... });
```

Per request. Never shut down. A thread pool allocated on a code path whose call rate is set by
clients, with no bound and no cleanup. Point a load test at that endpoint and watch the JVM
die.

The body wasn't much better: a `for` loop of five iterations with `Thread.sleep(2000)`, so the
stream ended after ten seconds regardless of what the task was doing. A task that finished in
twelve seconds got a stream that quit before the result existed. That is not streaming, it is a
stub with a `text/event-stream` content type.

Now there's one bounded `ThreadPoolTaskScheduler` in `AsyncConfig`, shared by every open
stream, with `removeOnCancelPolicy` so cancelled polls leave the queue immediately, and Spring
shuts it down with the context. The poll cancels itself when the task actually reaches a
terminal status, and `onCompletion` / `onTimeout` / `onError` all cancel it too — a client that
disconnects must not leave a poll querying the database and writing into a dead emitter until
the task happens to finish.

`TaskStatus.isTerminal()` exists now instead of comparing status name strings at each call
site.

One thing I wanted and couldn't have: `MdcTaskDecorator` on the scheduler, so poll logging
inherits the trace context. `ThreadPoolTaskScheduler.setTaskDecorator` arrived in Spring
Framework 6.2. This is Boot 3.3.2, which is Spring 6.1.11. The typecheck caught it. There's a
comment at the call site so the next person doesn't spend twenty minutes wondering why it isn't
there.

---

## 7. The flagship autoscaling scaled on a broker that does not exist

The ingress path migrated to Kafka and Debezium CDC. `docker-compose.yml` runs Kafka. There is
no `spring-boot-starter-amqp` in `pom.xml`. There is no AMQP broker in the stack.

Helm, KEDA, Argo Rollouts and Flagger were still wired to RabbitMQ. Seventeen references in
`values.yaml`. A KEDA trigger of `type: rabbitmq` reading `lineage.query.ingress.queue` over
`protocol: amqp`. `SPRING_RABBITMQ_LISTENER_*` environment variables projected into worker pods
for a listener that no longer exists. Zero occurrences of "kafka" anywhere under `helm/`,
`argocd/` or `flux/`.

The README called this "not yet ported". That undersells it. A scaler pointed at a queue on a
broker that isn't deployed doesn't scale badly — it reports nothing, so the worker tier sits at
`minReplicaCount` through any load you throw at it. The 50k-burst response the documentation
advertised was arithmetic about a system that had been deleted.

Worse was the canary gate. Argo's `AnalysisTemplate` failed a rollout if
`rabbitmq_queue_messages_published_total{queue="lineage.query.dlq.queue"}` went above zero.
That series is permanently absent, so the check was permanently green. A health check that
cannot fail is worse than no health check, because it buys you confidence you haven't earned. A
canary that was dead-lettering every single record would have been promoted to 100%.

Everything now targets Kafka: a `kafka` KEDA trigger on consumer-group lag for
`lineage.query.events` / `lineage-worker-group`, `SPRING_KAFKA_*` worker env, a Kafka secret and
an optional SASL `TriggerAuthentication` that only renders when you turn it on (an empty
`TriggerAuthentication` makes the scaler fail rather than fall through to anonymous, which is
not what anyone wants from a default). Canary analysis watches arrivals on
`lineage.query.events.dlt`. Docs, `NOTES.txt`, `Chart.yaml` and `scripts/validate_manifests.sh`
all follow.

### maxReplicaCount went from 50 to 12, on purpose

`ceil(50000 / 1000) = 50` is correct arithmetic for RabbitMQ, where any number of consumers can
compete for one queue. It is meaningless for Kafka. A partition is assigned to at most one
consumer in a group, so the fifty-first pod — and the thirteenth — joins the group, gets no
assignment, and idles while burning its resource requests.

The ceiling is `kafka.topicPartitions`, currently 12. If you want more parallelism, repartition
the topic first and raise both numbers together. **Check that 12 matches your real topic.** I
picked it as a documented default; I have no way to see your cluster.

---

## The test suite was passing the whole time

Worth being blunt about, because it's the reason six of these survived to be found by reading
the code.

`LineagePipelineOrchestratorTest` covered the retry-exhaustion path. It asserted
`assertEquals(TaskStatus.FAILED, task.getStatus())` and passed. It builds the orchestrator with
`new`. No Spring proxy, therefore no transaction, therefore no rollback. It was asserting that a
setter had been called on an object in memory — and the setter *was* called, so the test was
green, while the database kept none of it.

That is the worst kind of test. Not a test that fails, and not a missing test — a test that
tells you the exact behaviour you care about is working when it isn't. Pure-Mockito tests around
transactional code test Mockito.

Added `LineageTerminalFailureAuditTrailTest`, which runs against a real Spring context with a
real transaction manager, kills the pipeline, and asserts the FAILED status and the audit row
are still there *after* the rethrow rolls the phase transaction back. This is the test that
caught the `REQUIRES_NEW` deadlock in section 1. Had I trusted the Mockito test, I'd have shipped
a deadlock and called the bug fixed.

The old test stays, rewritten to assert delegation instead of pretend persistence, with a comment
saying what it can and cannot see. The pipeline logic it used to cover moved to
`LineagePipelinePhaseRunnerTest` and `PipelineFailureHandlerTest`.

Also added: actuator authorization and JWT rejection cases in `SecurityFilterChainTest`, and
decrypt-failure cases in `TcknEncryptionServiceTest`.

One deliberate weakening. My first `/actuator/health` test asserted 200, which is wrong — it
couples "security lets this through" to "every downstream dependency is up". It now asserts the
response isn't 401 or 403. The endpoint being publicly reachable is the property under test. 503
is a perfectly legitimate answer from a healthy security configuration.

**Not covered:** the SSE rework. The fix is structural — there is no per-request executor to leak
any more — but MockMvc async SSE testing is unpleasant enough that I didn't do it. If you want
that covered, it's honest work left undone, not something I'm claiming is fine.

---

## What I did not verify

Say what you ran. Don't imply more.

There is no Maven and no JDK 21 on this machine, and the project targets 21. I compiled with
`javac` 17 against the jars already in `~/.m2` and drove JUnit through a small launcher harness.
**52 tests, all passing.**

That is not the same as a green `mvn verify`, and you should run one.

- `LineageMessagingTestcontainersTest` never ran. Its Debezium testcontainers jar ships Java 21
  class files and it needs Docker. Untouched.
- Helm isn't installed, so no `helm template` and no `helm lint`. Instead I checked that every
  `values*.yaml` parses, that every `.Values.*` reference across all templates resolves to a
  defined key (zero missing), and that the `argocd/` and `flux/` manifests parse.
  `scripts/validate_manifests.sh` is updated to the Kafka assertions and has not been run.
- Nothing was executed against a real Kafka, a real Vault, a real Redis or a real cluster.

One thing worth stealing from this: the first two test runs failed with 500s on `@PathVariable`
endpoints and I nearly went hunting for a regression I'd introduced. The actual cause was that
plain `javac` doesn't pass `-parameters`, which the Spring Boot parent POM does, so Spring
couldn't resolve argument names by reflection. Environment, not code. Check your harness before
you accept a diagnosis from it.
