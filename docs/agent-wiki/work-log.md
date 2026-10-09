# Agent work log

## 2026-10-09 — Versioned LAN discovery heartbeat

- The proxy now broadcasts its existing discovery payload `{service,protocolVersion,apiPort,coins}` every second to UDP 8092 on physical IPv4 LAN broadcasts. The existing UDP 8091 request/reply listener remains for older PC-Agents.
- Virtual, Docker, veth, Tailscale and WSL interfaces are excluded; the PC-Agent still treats the packet source as an untrusted candidate and verifies service/version/ports plus `GET /api/network/ip`.
- Docker image metadata exposes UDP 8092 in addition to the legacy UDP 8091 query port. The managed loopback proxy continues to launch with discovery disabled.
- Verification: full `gradle test --offline` passed under JDK 21 (45 seconds). No two-host broadcast or firewall test was available.

## 2026-10-09 — Revenue projection day-factor correction

- Cause: both dashboard value formulas applied the 86,400-seconds-per-day factor twice. Accepted share work is already a hash count accumulated over the rolling 24-hour window, but `estimatedCoinsPerDay` multiplied it by blocks/day again. The projection similarly converted H/s to one day of work and then multiplied by blocks/day. Realised and projected USD values were therefore 86,400 times too high.
- Change: accepted work now uses `acceptedWorkHashes / networkHashrateHps / targetBlockSeconds * blockReward`; projected H/s uses `hashrateHps / networkHashrateHps * (86,400 / targetBlockSeconds) * blockReward`. Currency Service inputs and the dashboard HTTP schema are unchanged.
- Verification: a regression fixture proves that 100 TH/s on a 1 EH/s network with 600-second blocks, 3.125 coin reward and USD 60,000 price yields USD 2,700/day, and that the equivalent accepted hash count yields the same value. `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon` passed all 34 tests.
- Not verified: no running proxy was restarted and no live pool balance or payout was compared. Share-difficulty unit accuracy remains a separate pool/algorithm-specific evidence gate.

## 2026-10-07 — QTC job notification format and real SRBMiner reconnects

- Evidence: two local SRBMiner 3.7.1 QTC GPU consoles showed `PARSE error: Quantus notification has no job object` immediately before reconnection. The same sessions received initial jobs, reached about 350–385 MH/s and logged accepted shares; those miner-native counters are not pool-credit evidence. Earlier RVN/ETC local consoles showed sustained jobs and ended with an explicit Node configuration stop, not a comparable parser failure.
- Cause and change: the QTC proxy adapter sent follow-up `job` notifications with job fields directly under `params`; SRBMiner requires the object under `params.job`. Outgoing notifications now use the nested job shape, preserve `clean_jobs`, and accept direct or nested upstream notifications. The initial login response keeps its direct `result.job` shape. No QTC fee route was bypassed.
- Verification: focused `GpuStratumProtocolTest` and `GpuMinerSessionHandshakeTest` passed; `:pc-agent:standaloneJar --offline --no-daemon` rebuilt successfully with the embedded proxy change. The running miner was not restarted with that artifact; sustained connectivity and pool-side accepted shares remain open.

## 2026-10-07 — Internal GPU route frames excluded from miner telemetry

- Cause: each PC-Agent GPU relay connection writes an internal `solarminer.route` frame before SRBMiner sends any Stratum message. The proxy previously emitted `Miner connected` on that frame, so a route-only socket produced a false connected/disconnected pair even with the earlier TCP-only probe fix.
- Change: `MinerSession` processes protocol interception before opening visible miner telemetry, then restores the routed user-pool address once an actual miner frame arrives. No Stratum payload or fee-routing contract changed.
- Verification: `GpuMinerSessionHandshakeTest` covers route-only sockets for RVN/ETC/DCR/QTC and the real subscribe/authorize path; the full proxy `test --offline` suite passed. The PC-Agent standalone JAR was rebuilt with the updated embedded proxy source. A live operator log is still needed to distinguish route-only events from genuine SRBMiner/upstream reconnects in the reported running instance.

## 2026-10-07 — Independent market quotes for BTC/DCR/QTC dashboard

- `CurrencySnapshotService` still uses C9 network snapshots for gross earning estimates, but also reads the public `/coin-prices` map independently. The dashboard can show BTC/DCR/QTC USD-per-coin quotes even when the network row is absent; it labels this `PRICE ONLY` and does not invent an earnings estimate. Quotes older than two hours are withheld.
- The public Currency Service was observed to omit DCR/QTC prices and BTC/DCR/QTC network rows at the time of inspection, so the owning `currency-service` repository was updated and must be deployed separately. This proxy change cannot fill missing central quotes itself. Full proxy Gradle suite passed locally; no running proxy was restarted or deployed.

## 2026-10-07 — ETC pseudo-reconnects from readiness probes

- Operator log: ETC `Miner connected`/`Miner disconnected` pairs in the same second every ~2–5 s, with only one pair of upstream connections. The PC-Agent's five-second GPU monitor opened and closed a raw TCP socket for readiness; the proxy counted that socket at accept, before any Stratum frame. This is a telemetry false positive, not proof that a real miner reconnected.
- `MinerSession` now records a miner only after receiving its first frame, so TCP-only probes are invisible to miner telemetry. PC-Agent now reads existing `/api/dashboard` coin `listenerStatus` and configured port over HTTP instead of probing the Stratum socket. The proxy also handles optional `mining.extranonce.subscribe` locally; a bounded ETC Kryptex probe returned `Not supported` for that extension whereas 2Miners accepted it. See [GPU protocol record](gpu-stratum-repair-2026-10-06.md).
- Verification: full proxy `sh gradlew test --offline --no-daemon` passed, 24 tests, including TCP-only probe and local extension negotiation. Sibling PC-Agent suite and `standaloneJar` passed, 75 tests. The operator's live running process was not restarted or deployed; real miner stability, accepted user/house shares and pool credits remain open.

## 2026-10-06 — Repair GPU handshake, nonce routing and Quantus dialect

- Owner: GPU protocol adapters and MinerSession; C2 producer updated in Solar-Miner-Node/pc-agent and shared contract updated in admin-portal. Full findings, primary references and bounded real Kryptex login/job evidence: [repair record](gpu-stratum-repair-2026-10-06.md).
- Removed fixed subscribe/authorize ID assumptions, non-hex proxy job IDs and fabricated subscription nonces. RVN supports a target embedded in notify; ETC supports default difficulty 1. Extranonce updates are negotiated and retain their byte-length parameter. Failed subscriptions/authorizations and required fee failures stop with a sanitized reason rather than becoming silent fee bypass.
- Quantus now has a separate login/job/named-submit adapter; it preserves complete QTC job fields and routes each submit with its original pool job ID/session token. Live login/embedded job was observed, but submits and credits are still fixture-only/unverified.
- PC-Agent supplies the selected route before the miner handshake through a loopback relay. MinerSession consumes the preamble, replays late fee handshakes and configured-route reconnects, and guards closed/replaced connections. Standalone RVN/ETC ports are 3336/3337. Agent and external proxy must upgrade together; no deployment/restart occurred.
- Verification: JDK `/home/lukas/.jdks/graalvm-ce-21.0.2`; `sh gradlew test --offline --no-daemon` succeeded with **23 tests**, including real loopback Netty subscribe-first/fee-authorize coverage. `sh gradlew :pc-agent:test :pc-agent:standaloneJar --offline --no-daemon` in the Node repository succeeded; PC-Agent suite has **74 passing tests**. `git diff --check` passed in all three affected repositories. Context tests use ephemeral loopback ports rather than interfering with running proxy listeners.
- Open gates: actual SRBMiner submit/accepted user-house-referral shares, nonce changes with a real miner, start/stop on claimed hardware/OS, pool credits and payouts. Fee policy, fee service data and production routing flags were not changed.

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

## 2026-10-07 — Cross-coin revenue overview becomes the dashboard default

- `monitoring/RevenueOverviewService` aggregates every configured listener into one revenue statement and `/api/dashboard` now returns it as `overview` next to the existing per-coin `coins` array. No new HTTP path was added, so the PC-Agent `/proxy-dashboard/` allow-list (`/`, assets, `/api/dashboard[/**]`) still covers the page unchanged.
- `Revenue/24h` uses only shares the pool accepted in the last 24h (`ProxyTelemetryService` accepted-share work × Currency Service network data × price). `Projected/24h` extrapolates the rolling 15-minute hashrate estimate. The fee statement splits gross into user pool, `house == true` targets and everything else as referrer fee; a fee-backend response without the `house` flag therefore counts as referrer, not house.
- Profitability is realised revenue divided by the measured hashrate (`usdPer24hPerHps`), so a port without hashrate ranks at `0` and no network-theoretical value is invented. Only ports with connected workers, accepted shares or a measured rate are listed; idle listeners contribute no row.
- `CurrencySnapshotService.Snapshot` gained `usdPerDayFromAcceptedWork`, `projectedUsdPerDay` and `profitabilityUsdPerDayPerHps`; the stale/missing snapshot rule is unchanged and suppresses all revenue figures rather than showing a price-only guess.
- Frontend: the root page now opens on the revenue overview (KPI row, fee allocation bar, profitability ranking table sorted by USD/24h per hashrate) and the per-port pages stay reachable from the sidebar. The requested "public network inputs" table was removed again on user feedback.
- Verification: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test --offline --no-daemon` → BUILD SUCCESSFUL, 27 tests, including the new `RevenueOverviewServiceTest` (fee split, ranking order, stale-snapshot suppression, idle port excluded). Browser check `.codex-qa/proxy-dashboard-overview.cjs` (fixture API on 127.0.0.1:8123, Playwright) passed all assertions with no page errors at 1440px and 390px; screenshots in `.codex-qa/screenshots/proxy-dashboard-overview/`. It caught one real defect: the profitability unit rendered as `GHH/s`.
- Not verified: no live pool, no real accepted shares, no Currency Service round trip, no PC-Agent embedded mount. Power draw is not measured by the proxy, so profitability is efficiency and never net profit.

## 2026-10-07 — Stateful fee-target roll mode (operator-selectable)

- `FeeManager.rollNextJobTarget` keeps the historical stateless behaviour (`random`, `ThreadLocalRandom` per job) and gains a `stateful` mode: a per-coin urn of persistent credit counters (smooth weighted round-robin). Every roll adds each target's percentage (USER carries `100 - totalFee`) to its credit, the highest credit wins and pays 100 back, so the realised share stays within one job of the configured percentage instead of relying on large numbers.
- Mode is selected by `proxy.fee.roll-mode` (default `random`, documented in `application.properties`) and switchable at runtime via `setRollMode`. Credits survive fee-backend target refreshes (`reconcileRollState` keeps counters for still-present targets, new targets start at zero, removed targets are dropped).
- Verification: `JAVA_HOME=/home/lukas/.jdks/graalvm-ce-21.0.2 sh gradlew test` → BUILD SUCCESSFUL, 32 tests, including the new `FeeManagerRollModeTest` (exact 7.5/2.5/90 split over 10 000 rolls, refresh does not reset the schedule, removed targets dropped, unknown mode normalises to random).
- Not verified: no live pool session; the per-session `rollNextJobTarget` call sites (BTC/XMR protocols) were not exercised end-to-end. PC-Agent side is documented in the Solar-Miner-Node work log.

## 2026-10-08 — Fee tier plumbing (node/proxy)

- `FeeService` (proxy) holds a volatile `tier` (`solarminer.fee.tier`, default
  `node`), appends `&tier=` to both fee-backend fetch paths, and exposes
  `setTier()` which re-fetches immediately and clears the on-demand referral
  cache — the next rolled job already uses the new split. `FeeController`
  gained `POST /api/v1/fees/tier` (forcing surface for the PC-Agent/Node) and a
  `tier` query param on `GET /api/v1/fees/{coin}/targets` so readers see the
  same split the proxy enforces.
- Compatibility: without any tier signal the proxy behaves exactly as before
  (node tier, 2.5%); against an old fee-backend the extra query param is
  ignored. `FeeManager` needs no change — `updateTargets`/`reconcileRollState`
  already handle percentage changes without resetting stateful roll credits.
- Evidence: `sh gradlew test` green (JDK 21), including the existing
  FeeManager/FeeResponse suites. No live fee-backend probe performed.
