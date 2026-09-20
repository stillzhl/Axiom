# Tasks and acceptance gates

- [x] T1: Implement `axiom.git` (pure port): Git observation schema,
  change-set data model, path-safety classification as data (symlink /
  submodule / unsafe-path rules), observation validation via the 0001
  strict schemas, base/head/tree identity construction. No process
  execution; no new production dependencies. (Landed 2026-09-20, slice
  1: PR "feat: implement 0003 git observation (port + adapter)".)
- [x] T2: Implement `axiom.adapters.git`: the only namespace invoking
  the `git` executable. Read-only commands only (status/diff/rev-parse;
  no fetch, checkout, or mutation). Records `git --version` and exact
  command lines in provenance; reports incomplete enumeration as
  `:observation/incomplete`, never silently partial; classifies symlinks
  and submodules as typed entries without following or recursing.
  (Landed 2026-09-20, slice 1; `axiom.cli`'s `git-commit` helper moved
  here as `current-commit-sha`, behavior unchanged.)
- [x] T3: Implement artifact digesting and the `artifacts` table:
  SHA-256 over exact bytes, media type/size/location records, retention
  statuses (`retained`/`expired`/`superseded`), declared retention
  bounds, schema-v3 forward-only transactional migration owned by
  `axiom.store`. Rows are marked, never deleted; exceeding a bound is an
  operational failure with the reason named. (Landed 2026-09-20, slice
  2: PR "feat: implement 0003 artifact digesting and retention (schema
  v3)". `axiom.model/sha256-bytes` hashes raw bytes, distinct from the
  0001 canonical-EDN `digest`; `axiom.store` gains `record-artifact!`,
  `mark-artifact!`, `read-artifact`, `list-artifacts` with read-back
  validation; `recorded_seq` -1 sentinel reads back as nil; optional
  `:artifact/bytes` cross-check at record time.)
- [ ] T4: Implement `axiom.runner` (pure port) and
  `axiom.adapters.runner`: command registry schema with approved command
  IDs and structured argument slots, no shell interpolation (explicit
  `:runner/shell` flag recorded when used), process spawn with timeout,
  cancellation and output caps, outcomes `:completed` / `:timed-out` /
  `:cancelled` / `:output-capped`, Evidence record construction with
  `:trust/local-diagnostic` marking and named limitations (no
  CPU/memory/network isolation claimed).
- [ ] T5: Implement `axiom.cli observe-git` (`--repo PATH [--base REV]`),
  `axiom.cli digest` (`--path FILE`), `axiom.cli run` (`--command ID
  [--args ...]`): thin adapters over the pure paths; EDN reports; exit
  0/4/5 contract per R7; `observe-git` and `digest` never create or
  migrate ledger files and never mutate the observed repository.
- [ ] T6: Wire observations into the ledger: `:event/observation` and
  `:event/evidence` schema kinds validated by `axiom.ledger`, recorded
  through the 0002 append path (hash-chained, deduplicated, replay
  ordered), referenced in snapshots/bundles by artifact digest; replay
  shows the observations and evidence behind each decision.
- [ ] T7: Adversarial and boundary tests: symlinks escaping the worktree
  reported `:path/unsafe` and excluded from digestion; submodules not
  recursed; `..`-escaping paths rejected operationally; dirty worktree
  reports head plus dirty file list with no synthetic tree SHA;
  runner timeout/cancellation/output-cap produce `:incomplete` results
  that cannot satisfy an obligation (dependent obligation `:unknown`);
  retention-bound exceeded is operational; tampered artifact digest rows
  detected on read. Synthetic fixtures only.
- [ ] T8: Verification and `scripts/check` gates: extend `scripts/check`
  with 0003 gates (observe a synthetic git repo, digest a synthetic
  file, run a bounded synthetic command, record observation/evidence
  events in a ledger, replay and assert the recorded digests and trust
  marks; assert the 0001/0002 exit contracts are unchanged). Record exact
  commands, results and limitations in verification.md.

Acceptance: `./scripts/check` green (Temurin 17.0.20, Clojure 1.12.0)
with the 0003 gates; Git observation of a synthetic repo reproduces the
same base/head/tree identities and change enumeration on repeat runs;
symlink-escape and submodule cases are blocked or typed, never followed;
artifact digests match `sha256sum` on the same bytes; retention-bound
exceeded and runner timeout are operational failures (exit 5) with named
reasons; local runner evidence is marked `:trust/local-diagnostic` and
never promoted; the 0001/0002 exit contracts and decision bytes are
unchanged; CI green on a clean checkout. A green test run supplies
bounded test evidence, not a formal proof or production trust
attestation.
