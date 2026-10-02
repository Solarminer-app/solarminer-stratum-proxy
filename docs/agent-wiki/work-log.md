# Agent work log

## 2026-10-02 — Beta Docker publishing

- Scope: `beta` branch container publishing; Stratum wire/fee/discovery contracts unchanged.
- Change: `.github/workflows/docker-beta.yml` runs `clean check`, builds the native container for amd64 and arm64, then publishes commit-specific and moving `beta` multi-arch tags. It does not publish the production version tag or `latest`.
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
