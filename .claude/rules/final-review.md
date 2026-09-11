---
paths:
  - "**"
---

When a coding task has its code, tests, and comments written and the commit is about to be final, run `final-review`
in a fresh subagent over the change, hand it the task statement, and act on its substantive findings before
committing. Do not run it in the same context that wrote the change; the pass exists because the writer cannot see
what it left out.
