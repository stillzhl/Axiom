# Delivery roadmap

See [the full proposed design](design-plan.md) and
[the first bounded spec](../specs/0001-offline-kernel/requirements.md).

| Milestone | State | Next acceptance work |
| --- | --- | --- |
| M0 — Bootstrap | In progress | Review foundation, CI evidence; add pinned lint/formatter tooling |
| M1 — Pure offline kernel | **Complete** (2026-09-20) | M1 gate review [docs/gate-reviews/m1-offline-kernel.md](gate-reviews/m1-offline-kernel.md): all design-listed M1 deliverables and gate conditions satisfied by spec 0001 (T1–T7). Deferred, not papered over: JSON interchange, source digest/approval provenance reconciliation, richer evidence schemas, public schema export, pinned lint/formatter — recommended next bounded specs |
| M2 — Durable local state | **Complete** (2026-09-20) | M2 gate review [docs/gate-reviews/m2-durable-local-state.md](gate-reviews/m2-durable-local-state.md): all design-listed M2 deliverables and gate conditions satisfied by Verified specs 0002 and 0003 |
| M3 — GitHub observations | **Complete** (2026-09-20) | M3 gate review [docs/gate-reviews/m3-github-observations.md](gate-reviews/m3-github-observations.md): all design-listed M3 deliverables and gate conditions satisfied by Verified spec 0004; open item is a documented `--pr` spec-compliance amendment; next is the M1 milestone gate review, then the M4 spec |
| M4 — Enforced gate | **Complete** (2026-09-20) | M4 gate review [docs/gate-reviews/m4-enforced-gate.md](gate-reviews/m4-enforced-gate.md): all design-listed M4 deliverables and gate conditions satisfied by Verified spec 0005; next is the 0004 `--pr` amendment and M4 deferred ergonomics, then the M5 spec |
| M5 — Supervised execution | **Accepted** (2026-09-20) | Accepted (0006): supervised agent execution — context projection, proposal protocol, fake + process agent adapters, isolated workers, capability dispatch, leases/fencing, action outbox, patch admission, post-action verification, separately-authorized PR publication, self-development policy promotion gates (the 0002 `leases`/`action_outbox` items and the 0005-deferred promotion gates are in scope); automatic merge remains out of scope; implementation next |
| M6 — Pilot and release | Planned | Holdout evaluation, measurable reliability, distribution and support |

**Self-hosting is an explicit goal.** Progress from advisory evaluation of Axiom
PRs to guarded development after the M2–M5 gates; see [self-hosting](self-hosting.md).
A candidate version cannot govern its own policy or evaluator upgrade.

Immediate next slice: establish source-linked requirement/obligation revisions,
complete schema coverage and read-only `status`/`next` projections. Do not attach
live execution privileges to the synthetic offline evaluator.
