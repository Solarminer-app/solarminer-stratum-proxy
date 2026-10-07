# GPU Stratum repair — 2026-10-06

Owners: protocol adapters and MinerSession in this repository; miner connection adapter and launch arguments in Solar-Miner-Node/pc-agent. Shared contract C2. This is source/test evidence, not accepted-share or payout certification.

## Confirmed Quantus mismatch

Two bounded TCP probes to qtc.kryptex.network:7049 were performed without a real payout address or any submitted share. `mining.subscribe` received no reply within five seconds. `login` with named params (`login`, `pass`, `agent`) immediately returned:

```json
{"id":72,"error":null,"result":{"extensions":["keepalive"],"id":"<pool-session>","status":"OK","job":{"difficulty":18253611008,"extranonce":"160478ab","job_id":"0ae6900e_18253611008","mining_hash":"<64 hex characters>","target":"<128 hex characters>"}}}
```

Even the deliberately invalid diagnostic login received this response: login success is NOT evidence of address validation or payout eligibility. Connections were closed after receiving the response. No share, GPU process, pool credit or fee target was exercised. Qelvpool independently documents the Quantus TCP `login` dialect and SRBMiner compatibility: [pool operator documentation](https://qelvhash.com/). [Kryptex QTC](https://pool.kryptex.com/qtc) documents port 7049 and SRBMiner.

The old Ethereum-like Quantus adapter was therefore replaced. `QuantusStratumProtocol` handles `login`, embedded login jobs and `job` notifications, rewrites only job IDs and named submit/session IDs, and preserves mining_hash, target, difficulty, extranonce, nonce and opaque proof fields. Fee-pool session tokens never replace the miner-visible user token; submit routing injects the origin pool's actual token. The observed login/job shape is factual; the named `submit` path is covered by a synthetic fixture, NOT a successful live share capture. Native Quantus QUIC remains unsupported.

## RVN/ETC and session repairs

- Correlate handshake response IDs to request methods, including string IDs; ID 2 is not inherently authorize. Optional extranonce rejection cannot be mistaken for failed authorization.
- Use hexadecimal proxy job IDs, retain original IDs per pool, and restore the miner's submit response ID only for its origin pool.
- ETC can deliver jobs with default difficulty 1 before an explicit difficulty notification. A target switch resets difficulty to 1 if that target never supplied another value.
- Eliminate invented subscription responses/nonces. The PC-Agent's loopback relay writes `{"method":"solarminer.route","params":["ADDRESS.sm1.BASE64URL_POOL.WORKER","x"]}` before copying miner bytes to the configured SolarMiner proxy. The proxy consumes this envelope and can connect before the real subscribe. It never forwards the preamble to a pool. Legacy subscribe-first GPU clients without it receive a clear error and must upgrade together with the proxy.
- Retain and replay the actual handshake for fee connections that finish after the miner's authorize. Broadcast later handshake messages with per-target credential translation.
- Negotiate extranonce updates through `mining.extranonce.subscribe`; preserve the complete prefix/size pair. Different nonce parameters without miner capability fail closed with a protocol error. QTC instead carries nonce metadata inside its jobs and does not use this Ethereum extension.
- Follow-up on 2026-10-07: the proxy now acknowledges `mining.extranonce.subscribe` locally and does not forward that optional request to USER or fee upstreams. A bounded diagnostic Kryptex ETC request returned `[20,"Not supported.",null]`, whereas 2Miners accepted it; forwarding the request could therefore break one side of a split session. The proxy still requires miner nonce-update capability before switching to an incompatible prefix/size.
- Pool `client.reconnect` reopens the configured route, never an arbitrary pool redirect, invalidates old jobs and replays the handshake. Three reconnects per target/session are allowed, then the session stops with a clear reason. A reconnect may still require disconnect if the miner cannot accept changed nonce parameters. Required fee failures intentionally remain fail closed; they must not turn into fee-free mining.
- Disconnect is idempotent, delayed connection callbacks cannot resurrect a closed miner, and an old upstream's close cannot disconnect its replacement. Lifecycle errors have a dedicated sanitized log/dashboard path and do not increment rejected-share counters.
- Default standalone listeners now include RVN 3336 and ETC 3337. Agent launch selects ETC `--esm 2` and RVN `--nicehash true`, per [SRBMiner's official parameter reference](https://github.com/doktor83/SRBMiner-Multi/blob/master/Parameters). These select documented modes, not proven runtime interoperability.

## Primary references

- [NiceHash EthereumStratum 1.0](https://github.com/nicehash/Specifications/blob/master/EthereumStratum_NiceHash_v1.0.0.txt): hex jobs, default difficulty, subscription and nonce behavior.
- [NiceHash extranonce subscription](https://github.com/nicehash/Specifications/blob/master/NiceHash_extranonce_subscribe_extension.txt): optional capability and false responses.
- [ethminer client](https://github.com/ethereum-mining/ethminer/blob/master/libpoolprotocols/stratum/EthStratumClient.cpp) and [kawpowminer client](https://github.com/RavenCommunity/kawpowminer/blob/master/libpoolprotocols/stratum/EthStratumClient.cpp): actual subscribe/extension/authorize request ordering and IDs.
- [KAWPOW pool server](https://github.com/RavenCommunity/kawpow-stratum-pool/blob/master/lib/stratum.js): RVN job/submit field preservation and optional extension rejection.

## Verification and rollout

`GpuStratumProtocolTest` covers arbitrary IDs, optional extension responses, real subscription forwarding, hexadecimal IDs, ETC default difficulty, fee job/submit origin, nonce size, missing capability and the observed QTC object shape. `GpuMinerSessionHandshakeTest` uses real loopback Netty/TCP sockets to verify a subscribe-first miner receives the pool nonce and its subsequent authorize reaches USER and HOUSE. PC-Agent `GpuStratumRelayTest` verifies preamble order, byte preservation in both directions, miner reconnect and resource closure. Full proxy and PC-Agent suites and `:pc-agent:standaloneJar` are checked during this repair; final command outcomes are recorded in work-log.md.

No running service was restarted or deployed. Local proxy mode needs the rebuilt standalone PC-Agent; external proxy mode needs both upgraded PC-Agent and upgraded proxy. Keep live SRBMiner user/house/referral accepted shares, nonce switches, start/stop and real pool-credit/payout gates open for every coin. Builds and handshake success do not close those gates.

### 2026-10-07 ETC dashboard connection storm

The operator supplied a dashboard log with paired ETC connected/disconnected events in the same second every ~2–5 seconds, while upstream connections appeared only once. Code inspection traced the periodic raw TCP connect/close to PC-Agent `GpuCoinMinerService.monitor` → `ProxyConfigurationService.miningReady` → `stratumReachable`; it ran every five seconds and the proxy recorded a miner at TCP accept, before any JSON frame. A naked TCP probe is not a Stratum miner. PC-Agent now reads the existing `/api/dashboard` coin `listenerStatus`/port, and `MinerSession` records a miner only after its first frame. A TCP-only session yields no miner telemetry. This diagnosis explains the event storm; it does not prove every real miner connection is stable after deployment.

The PC-Agent's GPU loopback relay sends `solarminer.route` before any bytes from SRBMiner. That internal frame is also not miner activity: `MinerSession` now records the visible session only after the protocol has consumed the preamble and a real miner frame arrives. A route-only connection on RVN, ETC, DCR or QTC produces no `Miner connected/disconnected` pair. Genuine miner reconnects remain visible; diagnosing those requires the adjacent proxy error/upstream and miner-console lines.

### 2026-10-07 QTC miner reconnections from malformed job notifications

The local `quantus-NVIDIA-2` and `quantus-NVIDIA-3` SRBMiner 3.7.1 consoles showed a real QTC login, jobs and accepted shares, followed by `PARSE error: Quantus notification has no job object` and a reconnect five seconds later. The proxy had emitted follow-up `job` messages as `params: {job_id, ...}`. SRBMiner expects `params: {job: {job_id, ...}, clean_jobs: ...}`; its login response still carries the initial job directly in `result.job`. `QuantusStratumProtocol` now wraps outgoing job notifications, retains `clean_jobs`, accepts either direct or already wrapped upstream jobs, and keeps `result.job` direct during login. The focused protocol test covers both notification shapes and the origin-specific share-submit path. This is a verified local miner parser failure; sustained post-rebuild runtime and fee-pool accounting still need observation.
