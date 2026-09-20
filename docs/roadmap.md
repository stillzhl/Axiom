# Delivery roadmap

See [the full proposed design](design-plan.md) and
[the first bounded spec](../specs/0001-offline-kernel/requirements.md).

| Milestone | State | Next acceptance work |
| --- | --- | --- |
| M0 — Bootstrap | In progress | Review foundation, CI evidence; add pinned lint/formatter tooling |
| M1 — Pure offline kernel | First slice implemented | Source mappings/digests, richer evidence schemas, context/readiness commands, JSON interchange |
| M2 — Durable local state | **Complete** (2026-09-20) | M2 gate review [docs/gate-reviews/m2-durable-local-state.md](gate-reviews/m2-durable-local-state.md): all design-listed M2 deliverables and gate conditions satisfied by Verified specs 0002 and 0003 |
| M3 — GitHub observations | **Complete** (2026-09-20) | M3 gate review [docs/gate-reviews/m3-github-observations.md](gate-reviews/m3-github-observations.md): all design-listed M3 deliverables and gate conditions satisfied by Verified spec 0004; open item is a documented `--pr` spec-compliance amendment; next is the M1 milestone gate review, then the M4 spec |
| M4 — Enforced gate | Planned | Approved policy outside candidate, provider-bound checks and race-safe admission |
| M5 — Supervised execution | Planned | Fake agent, isolated workers, leases/outbox, one real adapter |
| M6 — Pilot and release | Planned | Holdout evaluation, measurable reliability, distribution and support |

**Self-hosting is an explicit goal.** Progress from advisory evaluation of Axiom
PRs to guarded development after the M2–M5 gates; see [self-hosting](self-hosting.md).
A candidate version cannot govern its own policy or evaluator upgrade.

Immediate next slice: establish source-linked requirement/obligation revisions,
complete schema coverage and read-only `status`/`next` projections. Do not attach
live execution privileges to the synthetic offline evaluator.
