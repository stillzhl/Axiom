# Verification record

## Spec acceptance evidence — 2026-09-20

This slice authored spec 0005 (M4 — enforced verification gate) to
Accepted. Docs-only: the change adds `specs/0005-enforced-gate/`
(requirements.md, design.md, tasks.md, verification.md,
acceptance.md) and updates the `docs/roadmap.md` M4 row. No changes
under `src/`, `test/` or `scripts/`.

Evidence:

- `git diff --check` — clean.
- `./scripts/check` (Temurin 17.0.20, Clojure 1.12.0): **137 tests,
  1651 assertions, 0 failures, 0 errors** — exactly the 0004 baseline;
  all 0001–0004 CLI gates pass unchanged. This is the inertness
  baseline: the spec PR changes no code and no test counts.
- Spec acceptance gates in `acceptance.md` are all checked for the
  spec slice; implementation acceptance gates are recorded as pending.
- No HomeKV-specific content: the only HomeKV mentions are the
  standard repository-boundary statements ("nothing HomeKV-specific,
  never here"), consistent with prior specs. All fixtures referenced
  are synthetic (`synth-*`).
- `docs/roadmap.md`: M4 row updated to "Accepted (0005): enforced
  verification gate — trusted evaluation, approved-policy loading,
  bound check publication, protected branch/merge-queue integration,
  governance authorization separation; implementation next". The 0005
  scope is exactly the design's M4 item list, so the row update is
  honest.

Claims: nothing is claimed Verified by this slice; no M4 milestone
acceptance is claimed. Spec status is Accepted (2026-09-20);
implementation (tasks T1–T8) is pending.

## Limits and deferred work

- Test evidence is bounded (synthetic fixtures), not a formal proof or
  production trust attestation.
- Agent execution, action dispatch, leases, the action outbox and
  isolated workers are M5; Axiom-driven self-hosting and its policy
  promotion gates are M5. Automatic merge is out of scope — an allow
  decision is not a merge instruction.
- If host enforcement cannot be configured, the implementation must
  report advisory mode instead of claiming enforcement (R8); external
  protection/permission changes require the repository owner's
  authorization.
- Approved-policy fixtures in this spec are synthetic and generic;
  real consumer policies live in consumer repositories.
