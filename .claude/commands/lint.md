---
allowed-tools:
- Bash
- Read
- Edit
- Grep
- Glob
argument-hint: <module-path>
description: Run codespell, checkstyle and markdownlint on a module and fix the findings.
---

Lint the module `$ARGUMENTS` with the project's three linters, then fix every finding
in the source.

If `$ARGUMENTS` is empty, ask which module to lint (a directory path relative to the
repository root, e.g. `qubership-nifi-common`) and stop until the user answers.

Treat `$ARGUMENTS` as a module directory path relative to the repository root. Run every
command **from the repository root** so the linter configs' relative paths resolve. All three
linters reuse the configs under `.github/linters/` - the same rules CI enforces.

## 1. Validate the module

- Confirm the directory `$ARGUMENTS` exists. If not, report it and stop.
- Check whether `$ARGUMENTS/pom.xml` exists. If it does not, skip checkstyle (step 4)
  with a note: the module is not a Maven project.

## 2. codespell: every text file in the module

```bash
codespell --config .github/linters/.codespellrc --skip "*/target/*" $ARGUMENTS
```

`--skip "*/target/*"` excludes Maven build output. If `codespell` is not on `PATH`,
print a one-line note and skip this step (do not fail).

## 3. markdownlint: `.md` files in the module

```bash
markdownlint-cli2 --config .github/linters/.markdownlint.json "$ARGUMENTS/**/*.md" "!**/target/**"
```

The `"!**/target/**"` negation glob excludes Maven build output. Use forward slashes in
the path: the CLI globs this argument, and a backslash path matches nothing on Windows.
Prefer `markdownlint-cli2`; fall back to `markdownlint` (markdownlint-cli) if only that
is installed, using its `--ignore` flag for the exclusion:

```bash
markdownlint --config .github/linters/.markdownlint.json --ignore "**/target/**" "$ARGUMENTS/**/*.md"
```

If neither CLI is on `PATH`, print a note and skip.

## 4. checkstyle: `.java` files in the module, via Maven

```bash
mvn -q -pl $ARGUMENTS checkstyle:check -Dcheckstyle.failOnViolation=false
```

`failOnViolation=false` lets Maven list every violation in one pass instead of aborting
on the first. Read the full structured list from `$ARGUMENTS/target/checkstyle-result.xml`.
If `mvn` is not on `PATH`, print a note and skip.

## 5. Fix every finding by hand

Use `Read` and `Edit`, not auto-fixers (no `codespell --write-changes`, no
`markdownlint --fix`). Fix the source, not the rules.

- **codespell**: correct the flagged spelling in the source.
- **markdownlint**: fix per the rule ID (line length, list indent, heading spacing, and
  so on).
- **checkstyle**: fix per the violation (Javadoc, imports, naming, whitespace, and so
  on).

**Never modify the linter configs** to silence a finding, leave `.codespellrc`,
`.markdownlint.json`, `sun_checks.xml`, and `suppressions.xml` untouched.

If a finding looks legitimate or deliberate, such as a real domain term codespell flags,
or a checkstyle violation that is an intentional exception, do not silence it in a config and
do not force an unwanted source change. Leave it as is and report it to the user with the
file, line, and why it appears intentional, so they can decide.

## 6. Re-run and confirm clean

Re-run each linter that reported issues. Repeat the fix/re-run loop until no findings
remain, except for any you have deliberately left for the user to decide.

## 7. Summarize

Report, per linter:

- issues found and issues fixed;
- anything skipped because its tool is missing;
- any findings left for the user to decide, each with file, line, and reason.

Configs are never modified by this command.