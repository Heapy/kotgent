# CI failure policy

Treat every CI failure as a release blocker except this known timing-sensitive case: local tests passed, and CI alone failed in `PtyTest.realPtyChecksPass` on `concurrent close runs teardown exactly once` at its 10-second timeout.

For that exact case, rerun the failed CI job once. If it repeats, show the evidence and require explicit user approval before tagging.
