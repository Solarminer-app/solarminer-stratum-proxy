# Agent work log

## 2026-10-03 — Kryptex RVN/ETC user-pool login

- Contract: the GPU adapter recognizes decoded user-pool hosts under `*.kryptex.network` and forwards `WALLET/WORKER` on authorize; other pools retain the prepared `WALLET.WORKER` format. Fee-target logins remain those from the fee backend. The PC-Agent still emits the same encoded `sm1` envelope.
- Evidence: Kryptex's official [RVN](https://pool.kryptex.com/rvn) and [ETC](https://pool.kryptex.com/etc) pages publish wallet/worker and TCP ports 7031/7033. `GpuStratumProtocolTest` covers both rewrites synthetically. Real SRBMiner submit, accepted user/house shares, fee switching and pool credits remain open; the code does not establish production support.

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

## 2026-10-03 — Kryptex RVN Subscribe-Form im GPU-Adapter

- Live-Probe über den eingebetteten Proxy: Kryptex RVN antwortete auf Subscribe mit `[null,"605132"]`, danach mit erfolgreicher Autorisierung und `mining.set_target`. Der GPU-Adapter las für RVN nur Feld 0 als Extranonce und gab deshalb keinen Notify-Job an den Miner weiter. Der Adapter verwendet jetzt Feld 1, falls Feld 0 kein Text ist; die bestehende 2Miners-Form bleibt erhalten.
- Verifikation: `GpuStratumProtocolTest` mit beiden Subscribe-Formen unter JDK 21 offline erfolgreich. Nach PC-Agent-Neustart lieferte eine erneute Live-Probe `mining.notify` über RVN-Port 3336. ETC lieferte bereits Difficulty und Notify über Port 3337. Es gab keinen realen Submit oder akzeptierten User-/House-/Referral-Share; beide Coins bleiben gesperrt. Siehe [Node-Integrationsprotokoll](../../../Solar-Miner-Node/docs/agent-wiki/rvn-etc-integration.md).

## 2026-10-04 — SRBMiner-Subscribe vor dynamischer Pool-Wahl

- Ursache: SRBMiner 3.7.0 wartet auf die Antwort zu `mining.subscribe` (id 1), bevor es `mining.authorize` (id 2) mit dem codierten Pool-Ziel sendet. Der GPU-Adapter verband den Upstream erst nach Authorize und ließ Subscribe unbeantwortet; der Miner meldete nach 15 Sekunden Pool-Timeout.
- Der GPU-Adapter sendet nun sofort eine vorläufige RVN- bzw. ETC-Subscribe-Antwort. Nach dem echten Upstream-Subscribe überträgt er dessen Extranonce als `mining.set_extranonce` und unterdrückt die doppelte Subscribe-Antwort. Der Fee-Ziel- und Submit-Ursprungscode blieb unverändert. Gezielte `GpuStratumProtocolTest`-Tests unter JDK 21 bestehen. Der neu gestartete PC-Agent mit eingebettetem Proxy erhielt live RVN- und ETC-Jobs von Kryptex; SRBMiner meldete aber auf diesem Host unabhängig vom Proxy einen GPU-Epoch-Fehler und 0 H/s. Keine Shares, Fee-Umschaltung oder Auszahlung verifiziert; keine Produktionsfreigabe. [Integrationsrecord](../../../Solar-Miner-Node/docs/agent-wiki/rvn-etc-integration.md).
