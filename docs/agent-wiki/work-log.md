# Agent work log

## 2026-10-03 — RVN / ETC Stratum feasibility probes, no listener enabled

- Owner: proxy protocol boundary. Local SRBMiner-MULTI 3.7.1 fake-pool captures and read-only live 2Miners probes recorded RVN KAWPOW and ETC ETCHash handshakes; see the [integration record](../../../Solar-Miner-Node/docs/agent-wiki/rvn-etc-integration.md). RVN receives `mining.set_target` and an eight-element notify; ETC receives `mining.set_difficulty`, `mining.set_extranonce` and a four-element notify. Both subscribe and authorize shapes differ. This rules out blindly selecting the existing BTC/Pearl protocol.
- Change: added separate `RavencoinStratumProtocol` and `EthereumClassicStratumProtocol` prototypes with a common GPU JSON-RPC router. It decodes a planned `ADDRESS.sm1.BASE64URL_POOL.WORKER` authorize username, records pool-specific target/difficulty and extranonce, rewrites job IDs, maps submits to the job's origin and restores response IDs. Failed authorization and pool-requested direct reconnect close the session. No listener port or production configuration was added.
- Verification: actual installed Windows SRBMiner on RTX 2080 Ti reached a local fake pool, and public 2Miners TCP endpoints returned jobs. Two bounded ETC fake-pool mining attempts with easy difficulty did not produce a submit and were stopped at their timeouts. `gradlew.bat check --offline` passed under JDK 21, including new fake-pool tests for both dialects and existing tests. No actual miner submit, accepted share, fee-target switch with a real miner, payout, or PC-Agent producer was observed. Keep both coins unavailable pending those gates.
- Contract update: the PC-Agent now produces the planned encoded authorize login in SRBMiner's `--wallet`; the fee backend has operator-supplied RVN/ETC house targets and emits `house:true`, which this proxy's `FeeTarget` DTO already accepts. The adapter remains without a listener or live fee-switch evidence, so this does not enable either coin.

## 2026-10-02 — Beta Docker publishing

- Scope: `beta` branch container publishing; Stratum wire/fee/discovery contracts unchanged.
- Change: `.github/workflows/docker-beta.yml` runs `clean check`, builds the native container for amd64 and arm64, then publishes commit-specific tags ending in `-beta`, per-architecture tags `latest-amd64-beta` / `latest-arm64-beta`, and the multi-architecture `latest-beta` manifest. It does not publish the production version tag or `latest`.
- Verification: source/workflow review only; no GitHub Actions run, Docker build or real pool test was run here. Docker Hub secrets and the arm64 runner must be available for a successful publication.

Append dated entries for protocol, fee or discovery changes: owner, wire shape, producer/consumer code evidence, tests, real pool and payout evidence, unsupported combinations, rollout gate, and updated docs.

## 2026-10-01 — wiki baseline

- Evidence: package layout under `v1/connection`, `v1/protocol`, `v1/fee`, `controller`.
- Verification: source inspection only; no live Stratum or pool check.

## 2026-10-02 — B06 / F21 XMR job ID round trip

- Owner: `solarminer-stratum-proxy`; protocol C1/C2 wire contracts unchanged.
- Evidence: `MoneroStratumProtocol.handleMessageFromPool` had rewritten the parsed `params.job_id` but cached/sent the original raw JSON. Submit lookup rewrote the parsed ID back but forwarded the untouched raw submit.
- Change: serialize the miner-facing modified job before cache/delivery; serialize the submit after restoring the origin job ID and route it to the recorded target. Also translate an object-shaped `result.job` login response through the same job registry/cache path; malformed/missing job IDs retain the existing raw-forward behavior.
- Existing working-tree edits: this file already had a whitespace-only change; `PearlStratumProtocol.java` had unrelated formatting changes. They are preserved.
- Verification: source/diff inspection only. No tests/build/live pool were run in this task. Fake-pool job→submit round trip, exact login-result fixtures, unknown/stale IDs, target switching and real accepted user/house/referral shares remain open.
- Status: locally implemented; B06 integration gates remain open. Next: characterize actual XMR login-result shape and add deterministic fake-pool round-trip coverage before live-pool accounting.
