# Stratum proxy agent wiki

Checked against `src/main/java` on 2026-10-01. Read [workspace contracts](../../../admin-portal/docs/encyclopedia/08-contracts.md) C1, C2 and C4 before changing wire behavior. This proxy is a local data-plane component; its fee source is a public central backend.

## Ownership and boundaries

| Concern | Owner | Rule |
| --- | --- | --- |
| TCP/session lifecycle | `v1/connection/`, `MinerSession` | Own connections, framing and session state; no fee policy. |
| Coin protocol | `v1/protocol/btc/`, `xmr/`, `pearl/`, `MiningProtocolFactory` | Parse and emit coin-specific Stratum messages. Add a protocol adapter for a new wire format. |
| Fee routing | `v1/fee/FeeService`, `FeeManager`, `v1/routing/` | Fetch targets and choose job origin; fee percentages originate in fee-backend. |
| Discovery and local API | `controller/ProxyDiscoveryServer`, `ProxyNetworkController`, `v1/FeeController` | Advertise and validate the local proxy; do not implement miner-specific discovery in protocol parsers. |

## Documentation catalog

- [Proxy help](../../HELP.md): local setup and behavior; check source for current ports and protocol support.
- [Cross-repo contracts](../../../admin-portal/docs/encyclopedia/08-contracts.md): fee payload, credentials and discovery.
- [New coin guide](../../../NEW-MINING-COIN-GUIDE.md): mandatory end-to-end steps; [Pearl record](../../../PEARL-INTEGRATION.md) for PRL.
- [Work log](work-log.md): verified changes and open pool gates.

## Extension and review queue

- The protocol factory already separates BTC, XMR and Pearl parsing. Preserve that boundary; avoid protocol conditionals in Netty connection handlers. A successful handshake is insufficient: verify submits, reconnects, fee routing and both user/house accounting on a real pool.
- `FeeService` and the fee-backend models mirror a cross-repository payload. Check unknown coin, missing `house`, empty/unavailable fee response and referral route behavior together.
- The local REST/discovery endpoints and internet-reachable fee backend have different trust boundaries. Treat changes to admin routing with that distinction in mind.
