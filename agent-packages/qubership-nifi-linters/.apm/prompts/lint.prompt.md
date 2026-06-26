---
description: Run codespell, checkstyle, markdownlint, editorconfig-checker and textlint on a module and fix the findings.
allowed-tools: [Bash, Read, Edit, Grep, Glob]
argument-hint: "<module-path>"
---

Lint the module `$ARGUMENTS` with the project's five linters, then fix every finding
in the source.

If `$ARGUMENTS` is empty, ask which module to lint (a directory path relative to the
repository root, e.g. `qubership-nifi-common`) and stop until the user answers.

Treat `$ARGUMENTS` as a module directory path relative to the repository root. Run every
command **from the repository root** so the linter configs' relative paths resolve.
codespell, checkstyle, markdownlint, and textlint reuse the configs under `.github/linters/`;
editorconfig-checker reads the formatting rules from the root `.editorconfig` and runs
with default settings (the repository has no `.editorconfig-checker.json`). These are the same
rules CI enforces.

Every command excludes Maven build output (`target/`), test fixtures
(`*/test/resources/*`), and APM agent content (`skills`/`rules`/`commands` under
`.claude`/`.cursor`/`.agents`), mirroring the `FILTER_REGEX_EXCLUDE` filter in
`.github/super-linter.env`.

## 1. Validate the module

- Confirm the directory `$ARGUMENTS` exists. If not, report it and stop.
- Check whether `$ARGUMENTS/pom.xml` exists. If it does not, skip checkstyle (step 4)
  with a note: the module is not a Maven project.

## 2. codespell: every text file in the module

```bash
codespell --config .github/linters/.codespellrc \
  --skip "*/target/*,*/test/resources/*,*/.claude/skills/*,*/.claude/rules/*,*/.claude/commands/*,*/.cursor/skills/*,*/.cursor/rules/*,*/.cursor/commands/*,*/.agents/skills/*,*/.agents/rules/*,*/.agents/commands/*" \
  $ARGUMENTS
```

`--skip` excludes Maven build output, test fixtures, and APM agent content. If
`codespell` is not on `PATH`, print a one-line note and skip this step (do not fail).

## 3. markdownlint: `.md` files in the module

```bash
markdownlint-cli2 --config .github/linters/.markdownlint.json "$ARGUMENTS/**/*.md" \
  "!**/target/**" "!**/test/resources/**" "!**/.{claude,cursor,agents}/{skills,rules,commands}/**"
```

The negation globs exclude Maven build output, test fixtures, and APM agent content. Use
forward slashes in the path: the CLI globs this argument, and a backslash path matches
nothing on Windows. Prefer `markdownlint-cli2`; fall back to `markdownlint`
(markdownlint-cli) if only that is installed, using its `--ignore` flag for the exclusions:

```bash
markdownlint --config .github/linters/.markdownlint.json \
  --ignore "**/target/**" --ignore "**/test/resources/**" \
  --ignore "**/.claude/{skills,rules,commands}/**" \
  --ignore "**/.cursor/{skills,rules,commands}/**" \
  --ignore "**/.agents/{skills,rules,commands}/**" \
  "$ARGUMENTS/**/*.md"
```

If neither CLI is on `PATH`, print a note and skip.

## 4. checkstyle: `.java` files in the module, via Maven

```bash
mvn -q -pl $ARGUMENTS checkstyle:check -Dcheckstyle.failOnViolation=false
```

`failOnViolation=false` lets Maven list every violation in one pass instead of aborting
on the first. Read the full structured list from `$ARGUMENTS/target/checkstyle-result.xml`.
If `mvn` is not on `PATH`, print a note and skip.

## 5. editorconfig-checker: every text file in the module

```bash
editorconfig-checker -exclude 'target/|/test/resources/|/\.(cursor|claude|agents)/(skills|rules|commands)/' $ARGUMENTS
```

editorconfig-checker reads the formatting rules from the root `.editorconfig`. With no
`-config` flag it runs with default tool settings -- the repository has no
`.editorconfig-checker.json` -- the same as CI. `-exclude` adds the build-output,
test-fixture, and APM agent content filters on top of the tool's built-in excludes. If
`editorconfig-checker` is not on `PATH`, print a one-line note and skip this step (do not
fail).

## 6. textlint: `.md` and `.txt` files in the module

```bash
npx textlint -f unix --config .github/linters/.textlintrc \
  "$ARGUMENTS/**/*.md" "$ARGUMENTS/**/*.txt"
```

textlint reuses `.github/linters/.textlintrc` (the `terminology` rule, e.g. `id` -> `ID`,
`github` -> `GitHub`), the same config CI's super-linter uses. Prefer the repository's local
CLI (`node_modules/.bin/textlint`) via `npx textlint`; if textlint is not installed, print a
one-line note and skip this step (do not fail).

If the module has no `.md` or `.txt` files, textlint exits non-zero with
`Not found target files` -- treat that as "nothing to lint" and move on, it is not a
failure.

textlint does **not** support `!`-negation in CLI glob arguments; it reads exclusions from a
`.textlintignore` file (pass one with `--ignore-path` if needed). If a finding lands in build
output (`target/`), a test fixture (`*/test/resources/*`), or APM agent content
(`skills`/`rules`/`commands` under `.claude`/`.cursor`/`.agents`), ignore it -- CI does not
lint those paths.

## 7. Fix every finding by hand

Use `Read` and `Edit`, not auto-fixers (no `codespell --write-changes`, no
`markdownlint --fix`, no `textlint --fix`). Fix the source, not the rules.

- **codespell**: correct the flagged spelling in the source.
- **markdownlint**: fix per the rule ID (line length, list indent, heading spacing, and
  so on).
- **checkstyle**: fix per the violation (Javadoc, imports, naming, whitespace, and so
  on).
- **editorconfig-checker**: fix per the `.editorconfig` rule (charset, final newline,
  trailing whitespace, indent style, and so on).
- **textlint**: correct the flagged term in the source per the suggested replacement (e.g.
  `id` -> `ID`).

**Never modify the linter configs** to silence a finding, leave `.codespellrc`,
`.markdownlint.json`, `sun_checks.xml`, `suppressions.xml`, `.textlintrc`, and `.editorconfig`
untouched.

If a finding looks legitimate or deliberate, such as a real domain term codespell flags,
or a checkstyle violation that is an intentional exception, do not silence it in a config and
do not force an unwanted source change. Leave it as is and report it to the user with the
file, line, and why it appears intentional, so they can decide.

## 8. Re-run and confirm clean

Re-run each linter that reported issues. Repeat the fix/re-run loop until no findings
remain, except for any you have deliberately left for the user to decide.

## 9. Summarize

Report, per linter:

- issues found and issues fixed;
- anything skipped because its tool is missing;
- any findings left for the user to decide, each with file, line, and reason.

Configs are never modified by this command.
