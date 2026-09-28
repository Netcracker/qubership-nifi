# Linter hook for Codex and Cursor

A `PostToolUse` hook that lints each file right after Codex or Cursor writes or edits it,
with codespell, editorconfig-checker, checkstyle, markdownlint, and textlint. It ships as the
`qubership-nifi-lint-hook` APM package and is wired up by `apm install`.

This package is the Codex and Cursor variant of `qubership-nifi-lint-hook-claude`. Its
`lint_changed_file.py` must stay identical to the copy in that package. See
`agent-packages/qubership-nifi-lint-hook-claude/.apm/hooks/README.md` in the qubership-nifi
repository for tool prerequisites, configuration lookup, the reason for two packages, and a
manual dry-run example.
