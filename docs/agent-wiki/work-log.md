# Agent work log

## 2026-10-06 — Decred Haste adapter, fee route remains disabled

- Owner: GPU Stratum adapter. Added a Decred protocol bean and local listener on 3338. The provisional `mining.subscribe` reply follows the published Haste shape (difficulty/notify subscription IDs, 12-byte extranonce2 field); the upstream extranonce and length are passed to the miner after the selected pool replies.
- The adapter reuses the JSON-RPC job-origin and submit routing, maps `mining.set_difficulty`, and validates DCR mainnet address prefix syntax. Suprnova publishes an SRBMiner `blake3_decred` endpoint, but its exact wire behavior has not been captured against this adapter. The PC-Agent now enables any coin only when the fee service returns a valid SolarMiner house target; no DCR target currently exists, so it remains unavailable automatically.
- Evidence: source review plus the Haste protocol reference and Suprnova setup page. No tests, pool session, accepted share, fee switch, accounting, or payout were verified. Keep production fee routing disabled until those gates pass.

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
## 2026-10-06 — Quantus (QTC) Stratum adapter prepared

- Added a canonical `quantusStratumProtocol` bean on listener port `3339`, using
  the shared GPU JSON-RPC job/submit-origin router and `mining.set_difficulty`.
  PC-Agent's standalone proxy launches the same listener.
- Declared API, discovery and all configured Stratum listener ports in Dockerfile
  image metadata. This is not a host firewall or `docker run -p` publication;
  deployment still must publish TCP 3339 to make external PC-Agents reach it.
- Candidate pool is Kryptex QTC (SRBMiner-compatible JSON-RPC Stratum); the
  Quantus native pool's WebSocket protocol and Quantus node miner's QUIC
  protocol are intentionally outside this Stratum adapter.
- No fee-backend QTC target exists because the SolarMiner QTC payout wallet is
  pending. PC-Agent enforces house-target readiness before miner start.
- Verification not run. No real subscribe/login/job/submit, accepted share,
  fee switch, pool credit or payout was verified; no production enablement.

## 2026-10-06 — Read-only Stratum operations dashboard

- Added a modern English dashboard at the proxy root and read-only `/api/dashboard` plus `/api/dashboard/console` endpoints. It reports configured listeners, connected miner sessions, per-target active upstreams, job-routing share, accepted/rejected shares, average share difficulty, and estimated hashrate.
- The in-memory telemetry observer retains no raw Stratum payloads. Currency snapshots are fetched from the public Currency Service in the background, cached for ten minutes, and used with accepted share work to estimate gross native-coin and USD value per hour. Estimates are hidden for missing or stale snapshots; they are not pool-account credits or payout records. Hashrate conversion is only enabled for algorithms with a configured share-difficulty unit; other coin rates remain unavailable rather than guessed.
- Console events are bounded, metadata-only, and redact credential-like fields. Upstream destinations are displayed as host and port without URL credentials. The UI is read-only and does not expose worker names, wallet data, or pool passwords.
- Verification: implementation/source inspection only; no tests, build, browser session, Currency Service live call, or real pool/accounting behavior was run in this task. Share response formats and difficulty units still need pool-specific fixtures before the corresponding estimate can be treated as accurate.

## 2026-10-06 — Dashboard mount path for the embedded PC-Agent

- Adjusted dashboard asset and API URLs to be relative, so the same page serves both at standalone proxy `/` and when mounted by PC-Agent at `/proxy-dashboard/`. The PC-Agent reverse route only forwards the dashboard's read-only endpoints; local embedded proxy listeners remain bound to `127.0.0.1:8090`.
- Verification: source inspection and `git diff --check` only; embedded-PC-Agent browser reachability remains unverified.

## 2026-10-06 — Embedded dashboard has a dedicated asset/API path

- Added `/embedded-dashboard/` asset serving from `static/proxy-dashboard/` plus API aliases under `/embedded-dashboard/api/dashboard[/**]`. This avoids the PC-Agent `static/index.html` winning the shared classpath root in the embedded runtime. The standalone dashboard still uses `/` and `/api/dashboard`.
- PC-Agent Gradle packaging copies the proxy assets into the dedicated resource path; successful `:pc-agent:compileJava :pc-agent:embeddedProxyClasses --offline --no-daemon` confirms compile and resource processing. A live browser request remains unverified.
