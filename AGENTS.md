# Contributor instructions

Read the applicable numbered spec before semantic edits. Record requirements,
design, tasks and acceptance gates first. Owner instructions may authorize a
bounded implementation scope; record their provenance accurately. Do not invent
independent review or mark broader milestones Verified from partial evidence.

Keep the core pure and adapters explicit. Policies are declarative data; never
evaluate consumer code as policy. Unknown or malformed required inputs cannot
admit actions. Keep consumer-specific configuration and evidence in consumer
repositories. Preserve the MIT license. Run `./scripts/check` and update the
spec verification record before publishing changes.
