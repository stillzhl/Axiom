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
- [x] T4: Implement `axiom.runner` (pure port) and
  `axiom.adapters.runner`: command registry schema with approved command
  IDs and structured argument slots, no shell interpolation (explicit
  `:runner/shell` flag recorded when used), process spawn with timeout,
  cancellation and output caps, outcomes `:completed` / `:timed-out` /
  `:cancelled` / `:output-capped`, Evidence record construction with
  `:trust/local-diagnostic` marking and named limitations (no
  CPU/memory/network isolation claimed). (Landed 2026-09-20, slice
  3: PR "feat: implement 0003 diagnostic runner (port + adapter)".
  Checked-in `resources/axiom/run-registry.edn` holds four generic
  synthetic probes only; `run!`/`start!`/`cancel!` adapter API;
  `:run/complete? false` / `:run/result :incomplete` on every
  non-completed outcome.)
- [x] T5: Implement `axiom.cli observe-git` (`--repo PATH [--base REV]`),
  `axiom.cli digest` (`--path FILE`), `axiom.cli run` (`--command ID
  [--args ...]`): thin adapters over the pure paths; EDN reports; exit
  0/4/5 contract per R7; `observe-git` and `digest` never create or
  migrate ledger files and never mutate the observed repository.
  (Landed 2026-09-20, slice 4: PR "feat: implement 0003 CLI surface
  and observation/evidence ledger integration". Strict operand
  matching with a separate 0003 usage text (the 0001/0002 usage text
  is byte-identical); `:invalid` -> 4, anything else -> 5 via the
  shared mapping. `observe-git` emits the adapter's validated report:
  exit 0 when `:complete`, exit 5 when `:incomplete` (the failing step
  is named in the report). `digest` emits the artifact digest record
  (SHA-256 over exact bytes via `axiom.model/sha256-bytes`, validated
  `--media-type`, default `application/octet-stream`, byte size);
  `run` coerces `k=v` operands to the slots' declared types and exits 0
  on a completed run, 5 on an `:incomplete` record (timeout /
  output-cap, bound named). The 0001/0002 exit contracts are
  byte-identical.)
- [x] T6: Wire observations into the ledger: new `:observation` and
  `:evidence-record` payload record kinds validated by `axiom.ledger`,
  recorded through the 0002 append path (hash-chained, deduplicated,
  replay ordered), referenced in snapshots/bundles by artifact digest;
  replay shows the observations and evidence behind each decision.
  (Landed 2026-09-20, slice 4: same PR as T5. New payload record kinds
  `:observation` (`{:record/kind :observation :observation <git
  observation>}`) and `:evidence-record` (`{:record/kind
  :evidence-record :evidence <run record>}`) with exact-shape
  validation plus the pure ports' validators; pure constructors
  `ledger/record-observation` and `ledger/record-evidence` (candidate
  ID is the content digest via `axiom.model/candidate-id`); trust
  forgery (anything but `:trust/local-diagnostic`) is `:invalid`;
  `extract-events`/`ledger-world` treat the new kinds as zero 0001
  events (0001/0002 decision bytes unchanged); `replay-report` gains
  `:observations` and `:evidence` entries (seq, event-id, record);
  snapshots cover them by sequence with no special-casing; `:event/id`
  and `dedup/key` reuse rejected deterministically by the existing
  store behavior.)
- [x] T7: Adversarial and boundary tests: symlinks escaping the worktree
  reported `:path/unsafe` and excluded from digestion; submodules not
  recursed; `..`-escaping paths rejected operationally; dirty worktree
  reports head plus dirty file list with no synthetic tree SHA;
  runner timeout/cancellation/output-cap produce `:incomplete` results
  that cannot satisfy an obligation (dependent obligation `:unknown`);
  retention-bound exceeded is operational; tampered artifact digest rows
  detected on read. Synthetic fixtures only. (Landed 2026-09-20, slice
  5: PR "test: verify 0003 implementation (boundary tests, check gates,
  verification record)". Audit against the T7 list found most cases
  already covered by slices 1–4 — symlink-escape classification
  (committed, dirty, pure), submodule typing without recursion,
  `..`-escape → `:operational`, dirty worktree (head + dirty list, no
  dirty-tree key), runner timeout/cancel/output-cap → `:incomplete`
  with named bounds, retention-bound failures with bound and actual
  named, tampered-row detection on read. Added only the two genuine
  gaps: `unsafe-symlink-excluded-from-digestion` (the escaping link's
  target content never enters the observation; the entry carries
  exactly the change-entry fields, raw link text and reason) and
  `incomplete-runs-cannot-satisfy-obligations` (data-level gate:
  only a clean completed `:pass` run can satisfy an evidence
  obligation; timeout/cancel/output-cap records are rejected).)
- [x] T8: Verification and `scripts/check` gates: extend `scripts/check`
  with 0003 gates (observe a synthetic git repo, digest a synthetic
  file, run a bounded synthetic command, record observation/evidence
  events in a ledger, replay and assert the recorded digests and trust
  marks; assert the 0001/0002 exit contracts are unchanged). Record exact
  commands, results and limitations in verification.md. (Landed
  2026-09-20, slice 5: same PR. `scripts/check` gains a 0003 section:
  synthetic repo (init, commit, branch, dirty change, symlink) →
  `observe-git` exit 0 with identical base/head/tree SHAs on repeat
  runs (also matching `git rev-parse` ground truth), dirty file list,
  no synthetic tree SHA, symlink typed; `digest --path` exit 0 with the
  digest equal to `sha256sum` on the same bytes; `run true-probe`
  exit 0 with `:trust/local-diagnostic`; `run sleep-probe
  --args seconds=30` exits 5 with `:timed-out` (5 s registry timeout);
  observation + evidence events recorded through the 0002 append path
  in a synthetic ledger, `replay --ledger` asserts the recorded head
  SHA, stdout digest and trust marks; `observe-git --repo
  /nonexistent`, `digest --path /nonexistent`, `run --command bogus`
  exit 4; `observe-git` on a non-repo dir exits 5. The 0001/0002
  gates run unchanged earlier in the script.)

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
