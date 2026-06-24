# Linter hook (checkstyle + codespell + markdownlint)

A Claude Code [`PostToolUse`](https://docs.claude.com/en/docs/claude-code/hooks) hook that
lints each file right after Claude writes or edits it, so lint problems are caught locally
instead of waiting for the CI `super-linter` workflow.

- **codespell** runs on every changed file.
- **checkstyle** runs on changed `.java` files.
- **markdownlint** runs on changed `.md` files.

All reuse the project's existing configs:
`.github/linters/.codespellrc`, `.github/linters/sun_checks.xml`, and
`.github/linters/.markdownlint.json` (the same rules CI, `maven-checkstyle-plugin`,
and the super-linter use).

When a file has linting issues, the hook reports them back to Claude (so it can fix them).
It never hard-blocks your edits.

## Wiring

Registered in `.claude/settings.json`:

```json
{
    "hooks": {
        "PostToolUse": [
            {
                "matcher": "Write|Edit",
                "hooks": [
                    {
                        "type": "command",
                        "command": "python \"$CLAUDE_PROJECT_DIR/.claude/hooks/linters-hook/lint_changed_file.py\""
                    }
                ]
            }
        ]
    }
}
```

The script path is anchored to `$CLAUDE_PROJECT_DIR` (the directory Claude Code was started
in) so the hook resolves the script from any working directory, including a subdirectory.

## Prerequisites

| Tool               | Needed for          | Install                                                                 |
|--------------------|---------------------|-------------------------------------------------------------------------|
| `python`           | running the hook    | already required for codespell                                          |
| `codespell`        | spell checking      | `pip install codespell`                                                 |
| `java` (JDK)       | running checkstyle  | any JDK on `PATH`                                                       |
| checkstyle jar     | checkstyle rules    | download manually, see below                                            |
| `markdownlint` CLI | linting `.md` files | `npm i -g markdownlint-cli2` (preferred) or `npm i -g markdownlint-cli` |

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
points at a missing file, only `codespell` runs.

## How it works

The hook reads the `PostToolUse` JSON payload on stdin, takes `tool_input.file_path`,
and runs the linters from the repository root (so the configs' relative paths - e.g. the
`SuppressionFilter` reference to `.github/linters/config/suppressions.xml` - resolve).
On findings, it writes a summary to stderr and exits `2`; otherwise it exits `0` silently.

Manual dry-run:

```bash
echo '{"tool_input":{"file_path":"path/to/File.java"}}' | python .claude/hooks/linters-hook/lint_changed_file.py
```
