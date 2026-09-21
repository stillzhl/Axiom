# Self-hosting runbook — Axiom governing Axiom

How to run a self-driven Axiom change through the spec 0006
supervised-execution machinery (`axiom.supervisor`, `axiom.execute`,
`axiom.adapters.*`). This is the procedure the first self-hosting run
followed; its evidence is recorded in
`specs/0006-supervised-execution/verification.md` ("First self-hosting
run").

## Scope rule

Early self-driven tasks must be **bounded docs or adapter changes
outside the trust-critical kernel**. The kernel is the set of
namespaces in `axiom.execute/kernel-namespaces`
(`axiom.nomos`, `axiom.ledger`, `axiom.store`, `axiom.gate`,
`axiom.policy`, `axiom.contract`, `axiom.model`, `axiom.world`,
`axiom.prover`, `axiom.capability`); writes touching them under a
standard task are denied with
`:self-modification-requires-promotion`. Self-modifying tasks go
through the R11 promotion path (pinned previous evaluator release,
authorizer `:governance/promotion-accepted`) — never through a plain
run.

## The guarded sequence

Each run executes the same order; any denial or failure stops the
run with a named reason, never a silent partial result:

1. **Task admission** — the task EDN is read; malformed input is
   rejected (exit 4). Recorded as `:task/accepted` in the ledger.
2. **Lease acquisition** — `axiom.execute/acquire-lease` issues a
   fencing token binding every later step to this worker.
   Recorded as `:lease/acquired`.
3. **Budget check** — `axiom.execute/check-budgets` against the
   task's `:budget/*` limits; `:budget-exhausted` blocks the run.
4. **Proposal loop** — the agent (a real `:process` subprocess, see
   below) emits a proposal; `axiom.execute/evaluate-proposal`
   admits or denies it on fencing token, lease holder, scope,
   capability grant, path safety, and task class. Prompt-shaped
   payloads in agent-supplied fields are denied with
   `:prompt-scope-escape`. Denials stop the run as `:task/blocked`.
5. **Patch admission** — the worktree is diffed against the pinned
   base; `axiom.execute/admit-patch` requires the diff to be
   covered by an admitted proposal, path-safe, and presented under
   the current fencing token, with an approved policy event.
6. **Post-action verification** — the supervisor runs the task's
   pinned recipe **for real** in the worktree and
   `axiom.execute/verify-patch` binds the exit code and output to
   the admitted patch digest. A nonzero exit fails the run with
   `:verification-failed`; a recipe that is not the pinned recipe
   is `:recipe-mismatch`.
7. **Ledger evidence** — `:patch/admitted` (with patch digest and
   evidence digest) and `:task/completed` are appended through the
   0002 hash-chained append path.
8. **Publication gate** — a PR is published only when a recorded
   `:governance/publication-authorized` event names the task.
   Without it the patch stays local. There is deliberately **no
   merge code path anywhere**; merging stays a human governance
   decision.

## Task EDN shape

```clojure
{:task/id "selfhost-<name>-1"            ; string, required
 :task/class :task-class/standard        ; or :task-class/self-modifying (R11)
 :task/capabilities {:capability/write-file #{"docs/"}} ; explicit grants only;
                                        ; :capability/shell defaults to denied
 :task/scope {:scope/path-prefixes #{"docs/"}}
 :task/pinned-recipe ["sh" "-c" "..."]   ; executed byte-for-byte by the supervisor
 :task/budget/max-attempts 3
 :task/budget/max-wall-seconds 300
 :task/publication-authorized            ; governance event, recorded before any
 {:event/kind :governance/publication-authorized  ; publication step
  :task/id "selfhost-<name>-1"
  :governance/policy-id "<authority>"}}
```

## The `:process` agent contract

The one real agent adapter spawns the worker as a bounded OS
subprocess: argv is fixed by the run (the adapter never invents
it), the working directory is confined to the isolated worktree,
credential-shaped environment variables are scrubbed, a wall-clock
timeout SIGKILLs on expiry, and stdout is parsed as EDN and
schema-validated as a proposal before the supervisor reads it.
The agent's proposal carries `:proposal/actions`; each
`:action/write-file` needs `:action/path` (in scope, path-safe),
`:action/capability` (granted on the task), and
`:action/content-digest` (`sha256:` of the bytes it wrote).

## Known limits (do not claim beyond these)

- The agent in early runs is scripted, not autonomous: the run
  proves the **governance** (admission, fencing, patch checks,
  verification, ledger evidence), not agent reliability.
- Prompt-escape resistance is tested against synthetic
  adversaries only.
- The process adapter confines the workdir and scrubs the
  environment; it is not a sandboxing proof.
- `axiom run-task --adapter process` does not yet plumb argv to
  the process adapter (found 2026-09-21: the run ends
  `:blocked :malformed`); real runs currently drive the same
  pure functions and adapters through a driver in the same order.
  Wiring argv through `run-task` is M6+ work.
