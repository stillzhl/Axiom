# Initial implementation handoff

Branch: `feat/offline-nomos-foundation`.
Base: initial `stillzhl/Axiom` main commit `c0c95a8`.
Publication is blocked by GitHub integration write permissions, not by tests.

The companion `axiom-offline-foundation.patch` contains the implementation and
verification commits as a Git mail patch. To import it into an unchanged clone:

```sh
git switch -c feat/offline-nomos-foundation
git am /path/to/axiom-offline-foundation.patch
./scripts/check
git push -u origin feat/offline-nomos-foundation
```

If the branch already exists, inspect and resume it instead of duplicating the
work. If main has advanced, reconcile the patch against the new repository state.

Suggested PR title: **feat: bootstrap offline Nomos kernel and self-hosting roadmap**

Suggested PR description:

> Axiom needs a deterministic foundation before it can supervise coding agents.
> This change introduces a bounded offline evaluator with strict EDN schemas,
> canonical candidate identity, separate claim/evidence event streams, and Nomos
> rules for accepted specs, dependencies, scope and candidate-bound test evidence.
> Unknown evidence defers, violated conditions deny, and only satisfied gates
> produce an advisory allow. Adds pinned JVM/Clojure bootstrap, CI, synthetic
> examples, an implementation spec, and staged self-hosting readiness gates.
>
> Validation: local and clean-checkout checks pass, with 11 tests/655 assertions
> and the four expected CLI exit codes. Remote CI must be inspected after push.
>
> Limits: supplied observations/approvals are unauthenticated; no durable ledger,
> GitHub observation, worker isolation or execution/merge capability exists yet.
> Axiom is not yet driving its own development. M0/M1 remain in progress.
