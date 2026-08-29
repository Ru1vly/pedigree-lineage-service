# Documentation

Start with whichever matches what you're doing. They don't need to be read in order.

**[CODE_MAP.md](CODE_MAP.md)** — where things live, the lifecycle of one lineage query from
HTTP POST to dead-letter topic, the transaction boundaries, the threading model, and the
invariants you're not allowed to break. Read this before changing anything in
`infrastructure/pipeline` or `infrastructure/security`.

**[CONFIGURATION.md](CONFIGURATION.md)** — every property and environment variable that matters,
its default, and what breaks when it's wrong. Includes what the production startup guard
enforces, the procedure for rotating the TCKN encryption key, and the retry budget aimed at the
census backend written out as arithmetic rather than left to be discovered.

**[OPERATIONS.md](OPERATIONS.md)** — local bring-up, deployment, how autoscaling behaves and why
the replica ceiling is what it is, and a failure-mode list: what a given symptom actually means
and where to look.

**[TESTING.md](TESTING.md)** — what each test class proves, which ones structurally cannot
detect transaction bugs, and the setup traps (Redis bean-name collisions, `-parameters`, slice
dependencies) that will otherwise cost you an afternoon.

**[SECURITY_ARCHITECTURE_NOTES.md](SECURITY_ARCHITECTURE_NOTES.md)** — field-level TCKN
encryption (including why the read path is part of that control, not an afterthought to it),
Vault dynamic secrets, SPIFFE/SPIRE mTLS.

**[ARCHITECTURE_AND_CANARY_DEPLOYMENTS.md](ARCHITECTURE_AND_CANARY_DEPLOYMENTS.md)** — KEDA
consumer-lag autoscaling and progressive delivery in depth.
