# Testing

What each test class is for, which ones can lie to you, and the environment traps that will
waste your afternoon if nobody warns you about them.

```bash
mvn test                  # unit + Spring context tests
mvn verify                # adds the Testcontainers suite (needs Docker)
```

## The suite

| Class | Kind | What it actually proves |
|---|---|---|
| `SecurityFilterChainTest` | `@SpringBootTest` + MockMvc, **real filter chain** | Tokens validated, roles enforced, actuator locked down, rate limiting wired into the authenticated path |
| `TcknEncryptionServiceTest` | plain unit | Encrypt/decrypt/mask round trips, and that decrypt fails loudly instead of returning ciphertext |
| `LineageTerminalFailureAuditTrailTest` | `@SpringBootTest`, **real transaction manager** | The terminal FAILED status and its audit row survive the DLT rethrow |
| `PipelineFailureHandlerTest` | Mockito | Retry-vs-terminal decision, outbox re-queue, real error code recorded |
| `LineagePipelineOrchestratorTest` | Mockito | Locking, delegation, rethrow-or-ack decision |
| `LineagePipelinePhaseRunnerTest` | Mockito | The three phases, terminal-status skip, unknown transaction |
| `LineageQueryControllerTest` | `@WebMvcTest`, filters off | Controller contracts and status codes in isolation |
| `FieldLevelEncryptionJpaTest` | `@SpringBootTest` + H2 + JDBC | TCKN is ciphertext *in the database column*, not just in the service |
| `LineageIntegrationTest` | `@SpringBootTest` | Service-layer ingress path |
| `LegacyCensusGraphClientTest` | unit | Census client behaviour and fallback |
| `VaultDynamicSecretsConfigTest` | unit | Vault dynamic secret configuration |
| `TcknValidatorTest` | unit | Checksum algorithm, including the invalid samples |
| `LineageMessagingTestcontainersTest` | Testcontainers, **Docker required** | Outbox → Debezium → Kafka for real |

`DockerAvailableCondition` gates the Testcontainers suite so it skips rather than explodes on a
machine without Docker.

## The thing to understand before you trust a green run

`LineagePipelineOrchestratorTest` used to cover retry exhaustion. It asserted

```java
assertEquals(TaskStatus.FAILED, task.getStatus());
```

and it passed. It also built the orchestrator with `new`. No Spring proxy, so no transaction, so
no rollback. It was asserting that a setter had been called on an object in memory — which it
had — while the database kept none of it, because the method under test rethrew and rolled the
whole transaction back.

A test that fails is useful. A missing test is at least honest. A test that reports the exact
behaviour you care about is working, while it isn't, is the worst of the three, and it is what
let the audit-trail defect survive review.

**Pure-Mockito tests around `@Transactional` code test Mockito.** If a behaviour depends on
commit, rollback, propagation, lazy loading, flush timing or row locks, a mock-based test cannot
see it. Write it against a real context.

`LineageTerminalFailureAuditTrailTest` is the counterexample: real Spring context, real
transaction manager, kill the pipeline, then assert the FAILED status and audit row are still
there *after* the rollback. It earned its keep immediately — it caught a `REQUIRES_NEW` deadlock
in the first attempted fix that every mock-based test in the suite was blind to. See
[`WHAT_WAS_BROKEN.md`](WHAT_WAS_BROKEN.md) section 1.

The Mockito tests stayed, rewritten to assert delegation rather than pretend persistence, with
comments stating what they cannot see. They are fast and they cover branching logic well. Just
don't ask them about transactions.

## Traps in the test setup

**`TestConfig`'s `@Primary` Redis mock does not always win.** `TestConfig` and the application's
`RedisConfig` both declare a bean *named* `stringRedisTemplate`. With
`allow-bean-definition-overriding: true`, one silently replaces the other by name and `@Primary`
never enters into it — it only breaks ties between distinct beans. Which one survives depends on
registration order, and adding an unrelated `@Component` can flip it.

Symptom: the real template gets built on a mocked `RedisConnectionFactory`, `getConnection()`
returns null, and you get

```
Connection is required
```

from somewhere that has nothing to do with what you were testing. In
`LineageTerminalFailureAuditTrailTest` it failed the pipeline at lock acquisition, so the test
recorded a completely different failure than the one being injected.

Fix: use `@MockBean StringRedisTemplate` in the test itself, which replaces the definition
outright, and stub `opsForValue()`. Don't rely on `@Primary` here.

**Slice tests need the SSE scheduler.** `LineageQueryController` takes a
`ThreadPoolTaskScheduler` for the progress stream. `@WebMvcTest` doesn't load `AsyncConfig`, so
the slice fails to start with an unsatisfied dependency. `LineageQueryControllerTest` declares
`@MockBean ThreadPoolTaskScheduler` — nothing there exercises the stream, so a mock is the right
weight. Importing `AsyncConfig` would also work and drag in more than the slice wants.

**Health is not asserted as 200.** `SecurityFilterChainTest` asserts `/actuator/health` returns
something other than 401 or 403. The property under test is that locking down `/actuator/**`
didn't take the probes with it. Whether every downstream dependency happens to be up is a
different question, and 503 is a legitimate answer from a correctly configured security layer.
Asserting 200 couples the two and produces failures that teach you nothing.

## What isn't covered

The SSE rework has no test. The thread leak is fixed structurally — there is no per-request
executor left to leak — but the polling lifecycle (cancel on terminal status, on timeout, on
client disconnect) is unverified. MockMvc async SSE testing is unpleasant, which is a reason and
not an excuse. If you touch that endpoint, this is the gap you are working over.

## Running it outside Maven

If you are stuck without Maven, this works, and it is how the fixes in `WHAT_WAS_BROKEN.md` were
verified: compile `src/main/java` and `src/test/java` with `javac` against the jars in
`~/.m2/repository`, then drive JUnit through `junit-platform-launcher`.

Two things will bite you.

**Pass `-parameters`.** The Spring Boot parent POM sets it; plain `javac` does not. Without it
Spring cannot resolve `@PathVariable` and `@RequestParam` names by reflection and every such
endpoint returns 500 with:

```
Name for argument of type [java.lang.String] not specified, and parameter name information
not available via reflection. Ensure that the compiler uses the '-parameters' flag.
```

This looks exactly like a regression you just introduced. It isn't. Check the harness before
you accept a diagnosis from it.

**A classpath of every jar in `~/.m2` is not this project's classpath.** It pulls in whatever
other projects left there. In one run that meant Spring AMQP was present, Boot auto-configured a
`RabbitHealthIndicator`, the indicator failed against a broker this stack does not run, and
`/actuator/health` returned 503 for reasons entirely unrelated to the code under test.

Neither substitutes for `mvn verify`. Run one before you believe anything.
