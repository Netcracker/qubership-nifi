#!/usr/bin/env python3
"""Claude Code PostToolUse hook: lint the file that was just written/edited.

Runs codespell and editorconfig-checker on every changed file, checkstyle on
changed .java files, and markdownlint on changed .md files. codespell, checkstyle,
and markdownlint reuse the project's existing configs under .github/linters/;
editorconfig-checker reads the formatting rules from the root .editorconfig and runs with
default settings (the repo has no .editorconfig-checker.json), the same as CI. Test
fixtures and APM agent content (skills/rules/commands) are skipped, mirroring the
FILTER_REGEX_EXCLUDE filter in .github/super-linter.env.

On findings the hook prints a summary to stderr and exits 2 so Claude Code
feeds the output back to Claude. Missing tools (codespell / editorconfig-checker /
java / the checkstyle jar / a markdownlint CLI) are reported as a note and skipped
-- they never block edits.

This script ships inside the qubership-nifi-linters APM package and is deployed via
`apm install` (its command is anchored to ${PLUGIN_ROOT}). Because the deployed location
is not fixed relative to the repository, the repo root is discovered at runtime rather than
from the script's own path: CLAUDE_PROJECT_DIR if set, else the current working directory
when it contains .github/, else `git rev-parse --show-toplevel`, else the working directory.
The linter configs (.github/linters/*, root .editorconfig) are expected in the consumer repo.

The checkstyle jar is NOT downloaded automatically: set the CHECKSTYLE_JAR
environment variable to a locally-downloaded checkstyle-*-all.jar. markdownlint
requires Node plus markdownlint-cli2 (preferred) or markdownlint-cli. See README.md.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


def find_repo_root() -> Path:
    """Locate the consumer repository root, independent of this script's location.

    Order: CLAUDE_PROJECT_DIR env var (set by Claude Code); else the current working
    directory if it already contains .github/; else `git rev-parse --show-toplevel`;
    else the current working directory as a last resort.
    """
    env_dir = os.environ.get("CLAUDE_PROJECT_DIR")
    if env_dir:
        return Path(env_dir).resolve()
    cwd = Path.cwd()
    if (cwd / ".github").is_dir():
        return cwd
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--show-toplevel"],
            cwd=cwd, capture_output=True, text=True, check=False,
        )
        top = (result.stdout or "").strip()
        if result.returncode == 0 and top:
            return Path(top).resolve()
    except OSError:
        pass
    return cwd


REPO_ROOT = find_repo_root()
CODESPELL_CONFIG = REPO_ROOT / ".github" / "linters" / ".codespellrc"
CHECKSTYLE_CONFIG = REPO_ROOT / ".github" / "linters" / "sun_checks.xml"
MARKDOWNLINT_CONFIG = REPO_ROOT / ".github" / "linters" / ".markdownlint.json"

# Mirrors FILTER_REGEX_EXCLUDE in .github/super-linter.env: skip test fixtures and
# APM agent content (skills/rules/commands). Hooks are intentionally left in scope.
# Matched against the absolute POSIX path so the leading ".*/" also catches
# repo-root content such as ".claude/skills/...".
EXCLUDE_PATTERN = re.compile(
    r"^(.+/test/resources/.*|.*/\.(cursor|claude|agents)/(skills|rules|commands)/.*)$"
)


def note(message: str) -> None:
    """Print an informational (non-blocking) note to stderr."""
    print(f"[lint] {message}", file=sys.stderr)


def read_file_path() -> Path | None:
    """Extract tool_input.file_path from the PostToolUse JSON payload on stdin."""
    # Read raw bytes and decode as UTF-8 so we don't depend on the console
    # codepage (e.g. cp1251 on Windows). The harness sends UTF-8 JSON.
    raw = sys.stdin.buffer.read().decode("utf-8", errors="replace")
    if not raw.strip():
        return None
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError:
        return None
    file_path = (payload.get("tool_input") or {}).get("file_path")
    if not file_path:
        return None
    path = Path(file_path)
    if not path.is_absolute():
        path = Path.cwd() / path
    return path.resolve()


def run_codespell(file_path: Path) -> str | None:
    """Return codespell output if misspellings are found, else None."""
    if shutil.which("codespell") is None:
        note("codespell skipped: not on PATH (install with `pip install codespell`)")
        return None
    cmd = ["codespell"]
    if CODESPELL_CONFIG.exists():
        cmd += ["--config", str(CODESPELL_CONFIG)]
    cmd.append(str(file_path))
    try:
        result = subprocess.run(
            cmd, cwd=REPO_ROOT, capture_output=True, text=True, check=False
        )
    except OSError as exc:
        note(f"codespell skipped: failed to run ({exc})")
        return None
    # codespell exits non-zero when it finds misspellings; output is on stdout.
    output = (result.stdout or "").strip()
    return output or None


def run_editorconfig_checker(file_path: Path) -> str | None:
    """Return editorconfig-checker violations if any, else None. Runs on every file.

    Reads the formatting rules from the repository's root .editorconfig and runs with
    default tool settings (no -config flag; the repo has no .editorconfig-checker.json),
    the same as CI's super-linter.
    """
    if shutil.which("editorconfig-checker") is None:
        note("editorconfig-checker skipped: not on PATH (see https://editorconfig-checker.github.io)")
        return None
    cmd = ["editorconfig-checker", str(file_path)]
    try:
        result = subprocess.run(
            cmd, cwd=REPO_ROOT, capture_output=True, text=True, check=False
        )
    except OSError as exc:
        note(f"editorconfig-checker skipped: failed to run ({exc})")
        return None
    # editorconfig-checker exits non-zero on violations and prints them to stdout.
    output = ((result.stdout or "") + (result.stderr or "")).strip()
    return output or None


def run_checkstyle(file_path: Path) -> str | None:
    """Return checkstyle violations if any, else None. Only for .java files."""
    jar = os.environ.get("CHECKSTYLE_JAR")
    if not jar:
        note("checkstyle skipped: set CHECKSTYLE_JAR to a checkstyle-*-all.jar path")
        return None
    jar_path = Path(jar)
    if not jar_path.is_absolute():
        jar_path = (REPO_ROOT / jar_path).resolve()
    if not jar_path.is_file():
        note(f"checkstyle skipped: CHECKSTYLE_JAR path does not exist ({jar})")
        return None
    if shutil.which("java") is None:
        note("checkstyle skipped: `java` not on PATH (a JDK is required)")
        return None
    cmd = [
        "java",
        # Force English output so it's consistent regardless of the dev's locale
        # (and so the framing-line filter below matches).
        "-Duser.language=en", "-Duser.country=US",
        "-jar", str(jar_path),
        "-c", str(CHECKSTYLE_CONFIG),
        str(file_path),
    ]
    try:
        result = subprocess.run(
            cmd, cwd=REPO_ROOT, capture_output=True, text=True, check=False
        )
    except OSError as exc:
        note(f"checkstyle skipped: failed to run ({exc})")
        return None
    # Strip checkstyle's framing lines; keep only the actual violations.
    lines = [
        line for line in (result.stdout or "").splitlines()
        if line.strip()
        and not line.startswith("Starting audit...")
        and not line.startswith("Audit done.")
    ]
    if not lines:
        # No violations parsed; surface stderr only if checkstyle itself errored.
        if result.returncode != 0 and (result.stderr or "").strip():
            return (result.stderr or "").strip()
        return None
    return "\n".join(lines)


# cli2's progress/summary framing lines we don't want to surface as findings.
_MARKDOWNLINT_NOISE = ("markdownlint-cli2", "Finding:", "Linting:", "Summary:")


def run_markdownlint(file_path: Path) -> str | None:
    """Return markdownlint violations if any, else None. Only for .md files.

    Auto-detects the CLI: prefer markdownlint-cli2 (matches CI's super-linter),
    fall back to markdownlint (markdownlint-cli).
    """
    cli = shutil.which("markdownlint-cli2") or shutil.which("markdownlint")
    if cli is None:
        note("markdownlint skipped: install markdownlint-cli2 (`npm i -g markdownlint-cli2`)")
        return None
    # Pass the file with forward slashes: the CLIs glob this argument, and on
    # Windows a backslash path is read as glob escapes and matches nothing.
    cmd = [cli, "--config", str(MARKDOWNLINT_CONFIG), file_path.as_posix()]
    try:
        result = subprocess.run(
            cmd, cwd=REPO_ROOT, capture_output=True, text=True, check=False
        )
    except OSError as exc:
        note(f"markdownlint skipped: failed to run ({exc})")
        return None
    # Both CLIs write violations to stderr; combine and drop cli2's framing lines.
    combined = ((result.stdout or "") + (result.stderr or "")).splitlines()
    lines = [
        line for line in combined
        if line.strip() and not line.lstrip().startswith(_MARKDOWNLINT_NOISE)
    ]
    return "\n".join(lines) if lines else None


def main() -> int:
    file_path = read_file_path()
    if file_path is None or not file_path.is_file():
        return 0
    # Ignore files outside the repo.
    try:
        file_path.relative_to(REPO_ROOT)
    except ValueError:
        return 0

    # Skip test fixtures and APM agent content, mirroring CI's super-linter filter.
    if EXCLUDE_PATTERN.match(file_path.as_posix()):
        return 0

    findings: list[str] = []

    codespell_out = None
    try:
        codespell_out = run_codespell(file_path)
    except Exception as exc:  # never let a linter crash block the edit
        note(f"codespell skipped: unexpected error ({exc})")
    if codespell_out:
        findings.append(f"codespell:\n{codespell_out}")

    editorconfig_out = None
    try:
        editorconfig_out = run_editorconfig_checker(file_path)
    except Exception as exc:
        note(f"editorconfig-checker skipped: unexpected error ({exc})")
    if editorconfig_out:
        findings.append(f"editorconfig-checker:\n{editorconfig_out}")

    if file_path.suffix == ".java":
        checkstyle_out = None
        try:
            checkstyle_out = run_checkstyle(file_path)
        except Exception as exc:
            note(f"checkstyle skipped: unexpected error ({exc})")
        if checkstyle_out:
            findings.append(f"checkstyle:\n{checkstyle_out}")

    if file_path.suffix == ".md":
        markdownlint_out = None
        try:
            markdownlint_out = run_markdownlint(file_path)
        except Exception as exc:
            note(f"markdownlint skipped: unexpected error ({exc})")
        if markdownlint_out:
            findings.append(f"markdownlint:\n{markdownlint_out}")

    if findings:
        rel = file_path.relative_to(REPO_ROOT)
        print(f"[lint] issues in {rel}:", file=sys.stderr)
        for finding in findings:
            print(finding, file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
