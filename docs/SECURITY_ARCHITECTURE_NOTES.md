# Security architecture

Three mechanisms, what each one actually defends against, and where each one stops. TCKN
encryption at rest, dynamic Vault credentials, and SPIFFE/SPIRE pod-to-pod mTLS.

Authentication and authorization are not in this document. Those live in
[`CODE_MAP.md`](CODE_MAP.md) under Security, and the history of getting them wrong is in
[`WHAT_WAS_BROKEN.md`](WHAT_WAS_BROKEN.md).

A note on how to read this. Being precise about what a control does *not* cover is the whole
value of a document like this — a control described in terms of what it might plausibly protect
is a control nobody can audit. Where a limit is stated below, it's a real limit, not modesty.

## 1. TCKN encryption at rest

### The threat

A DBA, or anyone holding a backup or a storage volume, must not be able to read plaintext
11-digit citizen identity numbers.

Disk-level encryption — EBS encryption, PostgreSQL TDE — does not achieve this. It defends
against someone walking off with the physical disk. It does nothing about an authenticated
`SELECT`, because the database decrypts transparently for anyone who can connect. If the threat
model includes privileged database users, and here it does, encryption has to happen above the
database.

### The mechanism

```
                                +-----------------------------------+
                                |    HashiCorp Vault (Transit Engine)|
                                +-----------------+-----------------+
                                                  |
                                    Encrypt/Decrypt REST Call
                                                  v
+------------------------+      +-----------------------------------+
|  Spring Boot 3 App     | ---> | TcknAttributeConverter (JPA)      |
|  (LineageQueryTask)    | <--- | (Encrypts on Write / Decrypts Read|
+------------------------+      +-----------------+-----------------+
                                                  |
                                      Persists Ciphertext Payload
                                                  v
                                +-----------------------------------+
                                | PostgreSQL / Database             |
                                | national_id VARCHAR(512)          |
                                | e.g. vault:v1:... or enc:v1:gcm:..|
                                +-----------------------------------+
```

`TcknAttributeConverter` is a JPA attribute converter, which is the right seam for this: it sits
on the field, so encryption happens on every write to the column and nowhere in the application
has to remember to call it. Add a new entity with a TCKN field, annotate it
`@Convert(converter = TcknAttributeConverter.class)`, and you are done. Forget the annotation
and you have written plaintext, so that annotation is the thing to check in review.

`VaultTransitTcknEncryptionService` prefers Vault Transit (`/v1/transit/encrypt/tckn-key`,
producing a `vault:v1:` prefix) and falls back to local AES-256-GCM envelope encryption
(`enc:v1:gcm:`) with a SHA-256 derived master key, random 12-byte IVs and 128-bit auth tags.
The fallback exists so the service runs without Vault in development and survives a Vault outage
in production.

Columns are `VARCHAR(512)` — `V2__encrypt_tckn_column.sql` widened them from `VARCHAR(11)` on
`lineage_queries` and `lineage_audit_logs`. Ciphertext is much longer than the plaintext it
replaces, which is obvious in hindsight and easy to forget when adding a new encrypted column.

### Decryption fails loudly, deliberately

`decrypt` throws `IllegalStateException` when it cannot produce real plaintext. It does not
return its input.

It used to. A `catch (Exception ignored) {}` fell through to `return cipherText`, so during a
Vault outage callers received an encrypted blob with no exception and no signal, and treated it
as a citizen's national ID — storing it, logging it, masking it for display, comparing it
against real TCKNs. Silent corruption of exactly the field this entire section exists to
protect.

If you see `Failed to decrypt ...` in production, Vault is unreachable or a key has changed.
Fix that. Do not make the exception go away.

### Verifying it

The point is the bytes in the column, not the behaviour of the service class, so the test goes
around the ORM entirely and reads with raw JDBC:

```java
String rawDbColumnValue = jdbcTemplate.queryForObject(
    "SELECT national_id FROM lineage_queries WHERE id = ?", String.class, taskId);

assertThat(rawDbColumnValue).startsWith("enc:v1:gcm:");
assertThat(rawDbColumnValue).isNotEqualTo("12345678950");
```

`FieldLevelEncryptionJpaTest` does this for both tables. A test that went through the repository
would prove nothing — the converter would decrypt on the way out and hand back plaintext that
looked correct whether or not the column was ever encrypted.

### What this does not cover

The application decrypts. Anyone who can read application memory, or make the application
decrypt for them, sees plaintext — this control is aimed at database access, not at application
compromise. Vault's own audit log, not this service, is where you see who decrypted what.
And masking (`TcknEncryptionService.mask`) is a display control, not a security boundary:
it stops TCKNs landing in log aggregators, and that is all it does.

## 2. Dynamic database credentials

Static long-lived database passwords sit in config, get copied into runbooks, and outlive the
people who provisioned them. Spring Cloud Vault issues a temporary PostgreSQL user with a 1-hour
TTL instead.

```
 1. Request 1-Hour Creds       +----------------------+
+----------------------------> | HashiCorp Vault      |
|                              | (database backend)   |
| 2. Re-issue Temp Role        +----------+-----------+
| (TTL: 3600s / 1h)                       |
|                                         v
+---------------+              +-----------------------+
| Spring Boot 3 | -----------> | SecretLeaseContainer  |
| Application   | <----------- | (Event Listeners)     |
+---------------+              +----------+------------+
        |                                 |
        | 3. Rotate Credentials           |
        v                                 v
+------------------+
| HikariDataSource |
| (PostgreSQL)     |
+------------------+
```

`VaultDynamicSecretsConfig` subscribes to `SecretLeaseCreatedEvent` and `SecretLeaseExpiredEvent`
for `database/creds/pedigree-db-role`, and runs a `@Scheduled(fixedRate = 1800000)` heartbeat —
every 30 minutes, against a 1-hour TTL, so there are two chances to renew before expiry. On new
credentials it updates the live `HikariDataSource` in place, so the pod does not restart and
in-flight work is not interrupted.

**PostgreSQL only.** An earlier version of this document claimed dynamic RabbitMQ credentials
rotated through a `CachingConnectionFactory` as well. There is no such code, there is no
`rabbitmq/creds/pedigree-rabbitmq-role` lease, and there is no RabbitMQ in this stack at all —
the ingress path is Kafka and Debezium. That paragraph described a system that did not exist,
which is worse than saying nothing, because a reader auditing broker credential hygiene would
have ticked the box and moved on. Kafka credentials are currently static, supplied through the
Helm-templated secret. If that matters for your compliance posture, it is open work, not a
solved problem.

`spring.cloud.vault.fail-fast` is `false`, so the application starts when Vault is unreachable
and falls back to the static datasource credentials and envelope encryption. That is a
deliberate availability trade: a Vault outage should not take down a citizen-facing service. It
does mean a long Vault outage leaves you running on static credentials without an obvious alarm,
so alert on the Vault health indicator rather than assuming startup would have told you.

## 3. SPIFFE/SPIRE and service mesh mTLS

IP-based trust inside a cluster is not trust. Every workload proves identity with a SPIFFE ID
and an X.509 SVID, and traffic between pods is encrypted with STRICT mTLS.

```
+-----------------------------------------------------------------------------------+
| Kubernetes Namespace: pedigree-system                                              |
|                                                                                   |
|  +------------------------------+             +--------------------------------+  |
|  | Pod: pedigree-api            |  STRICT     | Pod: pedigree-worker           |  |
|  |  +------------------------+  |  mTLS Tunnel|  +--------------------------+  |  |
|  |  | App Container          |  |  (X.509 SVID|  | App Container            |  |  |
|  |  +-----------+------------+  |  Certificates|  +------------+-------------+  |  |
|  |              | UDS Socket    | <=========> |               | UDS Socket     |  |
|  |  +-----------v------------+  |             |  +------------v-------------+  |  |
|  |  | Istio Envoy Sidecar    |  |             |  | Istio Envoy Sidecar      |  |  |
|  |  +------------------------+  |             |  +--------------------------+  |  |
|  +------------------------------+             +--------------------------------+  |
|                                                                                   |
|  +-----------------------------------------------------------------------------+  |
|  | SPIRE Agent (HostPath Socket: /run/spire/sockets/spire-agent.sock)           |  |
|  +-----------------------------------------------------------------------------+  |
+-----------------------------------------------------------------------------------+
```

`istio-mtls-spiffe.yaml` renders a `PeerAuthentication` in `STRICT` mode and an
`AuthorizationPolicy` restricting traffic to named SPIFFE principals — this service's own
service account, and the ingress gateway's.

Pods carry `sidecar.istio.io/inject: "true"`, `security.istio.io/tlsMode`, the
`SPIFFE_ENDPOINT_SOCKET` environment variable, and a read-only mount of the SPIRE agent socket
from the host path.

**One template detail that will bite you.** `mesh.spiffe.trustDomain` is stored as a full URI
(`spiffe://cluster.local`) because it is used verbatim elsewhere. Istio's `principals` list is
*not* prefixed — it wants `cluster.local/ns/<namespace>/sa/<account>`. The template therefore
runs `trimPrefix "spiffe://"` for that list specifically. Drop the `trimPrefix` and every rule
silently matches nothing, which in an `AuthorizationPolicy` means denying all traffic. It is
already commented in the template; it is repeated here because the failure mode is a total
outage that looks like a networking problem.

STRICT mode means exactly that: a workload without a sidecar cannot talk to one that has it.
When enabling this on an existing namespace, check what is *not* meshed first.

## Traceability

| Concern | Where |
|---|---|
| Encryption interface | [`TcknEncryptionService.java`](../src/main/java/com/edevlet/lineage/infrastructure/security/encryption/TcknEncryptionService.java) |
| Vault Transit + envelope engine | [`VaultTransitTcknEncryptionService.java`](../src/main/java/com/edevlet/lineage/infrastructure/security/encryption/VaultTransitTcknEncryptionService.java) |
| JPA converter | [`TcknAttributeConverter.java`](../src/main/java/com/edevlet/lineage/infrastructure/security/encryption/TcknAttributeConverter.java) |
| Column widening | [`V1__init_lineage_schema.sql`](../src/main/resources/db/migration/V1__init_lineage_schema.sql), [`V2__encrypt_tckn_column.sql`](../src/main/resources/db/migration/V2__encrypt_tckn_column.sql) |
| Encrypted entities | [`LineageQueryTask.java`](../src/main/java/com/edevlet/lineage/domain/model/LineageQueryTask.java), [`LineageAuditLog.java`](../src/main/java/com/edevlet/lineage/domain/model/LineageAuditLog.java) |
| Dynamic secrets | [`VaultDynamicSecretsConfig.java`](../src/main/java/com/edevlet/lineage/infrastructure/vault/VaultDynamicSecretsConfig.java) |
| Startup secret guard | [`SecretsConfigurationGuard.java`](../src/main/java/com/edevlet/lineage/infrastructure/security/SecretsConfigurationGuard.java) |
| Istio mTLS + SPIFFE policy | [`istio-mtls-spiffe.yaml`](../helm/pedigree-lineage/templates/istio-mtls-spiffe.yaml) |
| Workload API mounts | [`api-deployment.yaml`](../helm/pedigree-lineage/templates/api-deployment.yaml), [`worker-deployment.yaml`](../helm/pedigree-lineage/templates/worker-deployment.yaml) |

## Tests

`TcknEncryptionServiceTest`, `FieldLevelEncryptionJpaTest` and `VaultDynamicSecretsConfigTest`
cover this document's mechanisms. What each one proves, and which tests in this repository are
structurally incapable of catching certain classes of bug, is in [`TESTING.md`](TESTING.md).

This section used to carry a table of per-file pass counts and a `27 / 27` total. That number was
wrong within a week of being written, and a stale green number in a security document is worse
than no number, because it invites a reader to treat it as current evidence. Run `mvn verify`
and read the output — that is the only pass count worth trusting.
