# Stratum proxy agent wiki

Checked against `src/main/java` on 2026-10-01. Read [workspace contracts](../../../admin-portal/docs/encyclopedia/08-contracts.md) C1, C2 and C4 before changing wire behavior. This proxy is a local data-plane component; its fee source is a public central backend.

## Ownership and boundaries

| Concern | Owner | Rule |
| --- | --- | --- |
| TCP/session lifecycle | `v1/connection/`, `MinerSession` | Own connections, framing and session state; no fee policy. |
| Coin protocol | `v1/protocol/btc/`, `xmr/`, `pearl/`, `gpu/`, `MiningProtocolFactory` | Parse and emit coin-specific Stratum messages. Add a protocol adapter for a new wire format. |
| Fee routing | `v1/fee/FeeService`, `FeeManager`, `v1/routing/` | Fetch targets and choose job origin; fee percentages originate in fee-backend. |
| Discovery and local API | `controller/ProxyDiscoveryServer`, `ProxyNetworkController`, `ProxyHealthController`, `v1/FeeController` | Advertise and validate the local proxy; do not implement miner-specific discovery in protocol parsers. |

`GET /api/health` returns the service name and the `proxy.health-instance-id` passed at startup. The PC-Agent uses both fields to confirm that its current managed child, rather than another process on the same port, is answering. The endpoint does not certify Stratum listeners, fee routes or pool connectivity.

## Documentation catalog

- [Proxy help](../../HELP.md): local setup and behavior; check source for current ports and protocol support.
- [Cross-repo contracts](../../../admin-portal/docs/encyclopedia/08-contracts.md): fee payload, credentials and discovery.
- [New coin guide](../../../NEW-MINING-COIN-GUIDE.md): mandatory end-to-end steps; [Pearl record](../../../PEARL-INTEGRATION.md) for PRL.
- [Work log](work-log.md): verified changes and open pool gates.

## Extension and review queue

- The protocol factory separates BTC, XMR, Pearl and experimental GPU adapters. QTC uses its own `login`/`job`/named-submit adapter at `3339`, based on an observed Kryptex login response, not EthereumStratum. See [GPU Stratum repair](gpu-stratum-repair-2026-10-06.md) for evidence, the new PC-Agent route preamble and remaining live-share/accounting gates. Avoid coin protocol conditionals in Netty handlers.
- `FeeService` and the fee-backend models mirror a cross-repository payload. Check unknown coin, missing `house`, empty/unavailable fee response and referral route behavior together.
- The local REST/discovery endpoints and internet-reachable fee backend have different trust boundaries. Treat changes to admin routing with that distinction in mind.

## Operations dashboard

The root page serves a read-only English operations dashboard. `monitoring/ProxyTelemetryService` keeps bounded in-memory counters/events and discards raw Stratum messages. `/api/dashboard` reports session/target routing and `/api/dashboard/console` returns sanitized events. `CurrencySnapshotService` consumes the public Currency Service aggregate snapshots for indicative gross-value estimates only. These are not pool credits; missing/stale currency snapshots suppress the estimate. Consult the 2026-10-06 work-log entry for evidence and limitations.

When bundled into PC-Agent local mode, the dashboard is reached at the Agent's `/proxy-dashboard/` mount on port 8084. The underlying embedded proxy HTTP service remains loopback-only on port 8090; see the PC-Agent work log for the restricted reverse-route contract.

The embedded PC-Agent classpath has a different static root from the standalone proxy. Its dashboard assets are served explicitly at `/embedded-dashboard/`; do not rely on `/` in the embedded context because the PC-Agent owns the shared root `static/index.html`.
