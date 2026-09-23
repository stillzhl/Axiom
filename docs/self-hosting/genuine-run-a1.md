# Axiom genuine self-hosting run (Amendment A1)

This file was written by a worker subprocess driven end-to-end through
`axiom run-task --task <task.edn> --adapter process` -- the real CLI path,
not a driver script.

## Task

- Task ID: `axiom-a1-genuine-4`
- Agent argv: `["/usr/bin/python3", "<worker.py>"]` (absolute paths, validated per A1 AR7)
- Scope: `docs/self-hosting/` only (write-file capability, path-prefix grant)
- Task class: `:task-class/standard` (outside the trust-critical kernel)
- Pinned recipe: `./scripts/check` (the real suite, run for real in the worktree)
- Publication: authorized out-of-band by the main agent; recorded in the
  ledger as `:governance/publication-authorized` at admission (A1-S5)

## Governance applied to this run

1. Task admission -- `:task/accepted`, argv validated and recorded
2. Publication authorization -- `:governance/publication-authorized` (ledger-native)
3. Lease acquisition -- `:lease/acquired` with fencing token
4. Proposal admission -- worker-written file cross-checked byte-for-byte
   against the claimed `:action/content-digest` (A1 AR3)
5. Patch admission -- worktree diff admitted under the fencing token
6. Verification -- pinned recipe ran for real; `:patch/admitted` binds the
   patch digest to the evidence digest
7. Completion -- `:task/completed`
8. PR publication -- synthetic only (`synth-pr-*`); the real GitHub PR is
   opened through the external workflow as the publication boundary.
   Axiom's machinery does not merge; the merge decision stays with the
   main agent.

## What this run proves

The supervised-execution loop governs a real subprocess end to end:
validation-first request handling, one-shot process semantics (A1 AR10),
digest cross-checks, lease fencing, and ledger-backed publication
authorization -- all on the genuine CLI path with a hash-chained SQLite
ledger as durable evidence.

## Honest limits

- The process adapter is isolation-by-convention (worktree confinement,
  scrubbed env, timeout), not a sandbox proof.
- Prompt-injection resistance rests on synthetic adversarial tests.
- The run's PR step is synthetic; real publication is external.
