# Linter hook (checkstyle + codespell + editorconfig-checker + markdownlint)

A Claude Code [`PostToolUse`](https://docs.claude.com/en/docs/claude-code/hooks) hook that
lints each file right after Claude writes or edits it, so lint problems are caught locally
instead of waiting for the CI `super-linter` workflow. It ships as part of the
`qubership-nifi-linters` APM package and is wired up by `apm install`.

- **codespell** runs on every changed file.
- **editorconfig-checker** runs on every changed file.
- **checkstyle** runs on changed `.java` files.
- **markdownlint** runs on changed `.md` files.

codespell, checkstyle, and markdownlint reuse the consumer repository's existing configs:
`.github/linters/.codespellrc`, `.github/linters/sun_checks.xml`, and
`.github/linters/.markdownlint.json` (the same rules CI, `maven-checkstyle-plugin`,
and the super-linter use). editorconfig-checker reads the formatting rules from the root
`.editorconfig`; it has no tool config of its own (`.editorconfig-checker.json` / `.ecrc`)
in the repository, so it runs with default settings, the same as CI.

Test fixtures (`*/test/resources/*`) and APM agent content (`skills`/`rules`/`commands`
under `.claude`/`.cursor`/`.agents`) are skipped, mirroring the `FILTER_REGEX_EXCLUDE`
filter in `.github/super-linter.env`. Hook scripts stay in scope.

When a file has linting issues, the hook reports them back to Claude (so it can fix them).
It never hard-blocks your edits.

## Wiring

This hook is declared in `lint-changed-file.json` next to this `README.md` and deployed by
`apm install` / `apm compile`. The command is anchored to `${PLUGIN_ROOT}`, which APM
rewrites to the installed package root, so the script resolves from any working directory:

```json
{
    "PostToolUse": [
        {
            "matcher": "Write|Edit",
            "hooks": [
                {
                    "type": "command",
                    "command": "python \"${PLUGIN_ROOT}/.apm/hooks/lint_changed_file.py\""
                }
            ]
        }
    ]
}
```

Do not hand-edit the generated `.claude/settings.json` hook entry (or the Cursor / Codex
equivalents) - APM owns it and tracks it in the `.claude/apm-hooks.json` sidecar. Edit this
package and re-run `apm install` instead.

## Finding the consumer repository root

The linter configs live in the **consumer** repository, not in this package, so the script
discovers the repository root at runtime rather than from its own deployed path, in this order:

1. `CLAUDE_PROJECT_DIR` if set (Claude Code sets it);
2. the current working directory, if it already contains `.github/`;
3. `git rev-parse --show-toplevel`;
4. the current working directory as a last resort.

## Prerequisites

| Tool                   | Needed for            | Install                                                                 |
|------------------------|-----------------------|-------------------------------------------------------------------------|
| `python`               | running the hook      | already required for codespell                                          |
| `codespell`            | spell checking        | `pip install codespell`                                                 |
| `editorconfig-checker` | `.editorconfig` rules | see <https://editorconfig-checker.github.io/>                           |
| `java` (JDK)           | running checkstyle    | any JDK on `PATH`                                                       |
| checkstyle jar         | checkstyle rules      | download manually, see below                                            |
| `markdownlint` CLI     | linting `.md` files   | `npm i -g markdownlint-cli2` (preferred) or `npm i -g markdownlint-cli` |

If any tool is missing the hook prints a one-line note and skips that linter - it
will not block editing. With nothing installed, the hook is effectively a no-op.

The markdownlint step auto-detects the CLI, preferring `markdownlint-cli2` (what CI's
super-linter uses) and falling back to `markdownlint` (markdownlint-cli). Both need Node.

## Checkstyle jar (`CHECKSTYLE_JAR`)

The checkstyle jar is **not** downloaded automatically. Download a standalone
`checkstyle-<version>-all.jar` from the
[checkstyle releases](https://github.com/checkstyle/checkstyle/releases) and point the
`CHECKSTYLE_JAR` environment variable at it. Use **`13.2.0`** to match the
`maven-checkstyle-plugin` dependency in `pom.xml` for identical rules.

Set it in your shell profile, for example:

- `PowerShell`: `setx CHECKSTYLE_JAR "C:\tools\checkstyle-13.2.0-all.jar"` (new sessions)
- `bash`/`zsh`: `export CHECKSTYLE_JAR=/path/to/checkstyle-13.2.0-all.jar`

`CHECKSTYLE_JAR` may be absolute or relative to the repository root. When it is unset or
points at a missing file, checkstyle is skipped; the other linters still run.

## How it works

The hook reads the `PostToolUse` JSON payload on stdin, takes `tool_input.file_path`,
and runs the linters from the repository root (so the configs' relative paths - e.g. the
`SuppressionFilter` reference to `.github/linters/config/suppressions.xml` - resolve).
On findings, it writes a summary to stderr and exits `2`; otherwise it exits `0` silently.

Manual dry-run (from the consumer repository root):

```bash
echo '{"tool_input":{"file_path":"path/to/File.java"}}' \
  | python apm_modules/_local/qubership-nifi-linters/.apm/hooks/lint_changed_file.py
```
