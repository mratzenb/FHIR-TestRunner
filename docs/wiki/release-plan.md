# Plan: Conventional Commits, CHANGELOG and releases

[Home](README.md) › Release plan

**Status: plan, nothing implemented** (written 2026-11-17, commit `c39e481`). Pick it up with the tasks
[T1–T8](#tasks); the [open decisions](#open-decisions) come first. When a task is done, move what it decided into an
ADR (planned: `0021-conventional-commits-and-releases.md`) and delete or shrink this page.

## Goal

1. Conventional Commits, **checked and enforced on the pull request pipeline**. No local git hooks for now.
2. The commits are the base for **generating `CHANGELOG.md`** and for **determining the next version**.
3. Semantic versioning with two extra stages: `-SNAPSHOT` (development) and `-rc.N` (release candidate).
4. A release process. The pipeline must not modify files; a local script prepares the release.

## Findings (what exists, 2026-11-17)

| Topic | State |
|---|---|
| Workflows | `build.yml` (`verify`, `real-validator`; every PR, push to `main`, nightly), `image.yml` (`jar`, `image`; PR builds and tests, `main` and tags publish to GHCR). `permissions: contents: read`, actions pinned to a major version. |
| Tags | None yet. `image.yml` already reacts to `v[0-9]+.[0-9]+.[0-9]+*`, so `v1.2.0-rc.1` triggers it. |
| RC images | Verified in `docker/metadata-action` (`src/meta.ts`): for a semver tag **with a pre-release part** only the exact tag (`1.2.0-rc.1`) is made, **no `latest` and no `X.Y`**. `image.yml` needs no change for that. |
| Version | `0.1.0-SNAPSHOT` hard-coded in the root POM and in 7 module POMs (`<parent><version>`). Also named in `docker-compose.yml` (`image: testrunner:0.1.0-SNAPSHOT`), `.claude/skills/testrunner-overview/SKILL.md`, ADR-0020 ("POM stays 0.1.0-SNAPSHOT until a release is decided"). Doc examples use `v0.1.0` (`docs/wiki/ci-integration.md`, `action.yml`, `ci/testrunner.gitlab-ci.yml`). Not published to a Maven repository (only images). |
| Release docs | `docs/wiki/ci-integration.md` › "Releasing (maintainers)" (tag by hand). Must be rewritten by T6. |
| Commits | Linear history (no merge commits), free-form imperative sentences ("Fix cold-cache race …"), up to about 90 characters. Dependabot subjects: "Bump the spring-boot group …". |
| CHANGELOG | Hand-written prose with migration advice, headings "Changed – may change results", "Added", "Fixed", "Changed – API", under `## Unreleased (0.1.0-SNAPSHOT)`. A generator cannot reproduce that. |
| Tooling here | `gh` and `git-cliff` are not installed; `mvn` is not on the `PATH` (see skill `testrunner-build-and-run`). `npm` on the maintainer's machine points at a company registry (`jfrog.cgm.ag`) that is unreachable without the VPN
(`npm install --registry https://registry.npmjs.org` works). Remote: `git@github.com:mratzenb/FHIR-TestRunner.git`. The repository's merge settings and branch rules are **unknown**. |

## Part 1: Conventional Commits on the PR pipeline

* Format `type(scope)!: subject`. Types: `feat fix perf refactor docs test build ci chore revert`. Scope optional
  (warning, not error), from module names: `core fhir-hapi fhir-r4 fhir-r5 validation-cli autoconfigure app
  test-support image action ci docs deps`. Breaking: `!` or a `BREAKING CHANGE:` footer.
* New workflow `.github/workflows/commits.yml`, trigger `pull_request` (opened, edited, synchronize, reopened),
  `fetch-depth: 0`, `permissions: contents: read` (plus `pull-requests: read` if the action reads the title). It lints
  the **PR title** and **every commit of `base..head`**. History before the adoption is never checked. Use a stable job
  name; it is the required status check.
* Tool: **commitlint** with `@commitlint/config-conventional` (run via a pinned action or `npx`, config
  `commitlint.config.mjs`, no root `package.json` needed). Relax `subject-case` (the history uses sentence case, and
  Dependabot writes "Bump …") and `body-max-line-length`; keep `header-max-length` at 100.
* Dependabot (`.github/dependabot.yml`): `commit-message.prefix` `build(deps)` for Maven, `ci(deps)` for GitHub
  Actions, `build(docker)` for Docker, otherwise its PRs fail the check.
* **Enforcement needs repository settings**, a workflow alone enforces nothing: a ruleset on `main` that requires a
  PR, requires the `commits` status check, and blocks direct pushes (history today looks like direct pushes).
* Merge method: see [decision 1](#open-decisions). Recommended: squash-only, default message = PR title + description.
  Then the title is the commit on `main` and the changelog line.

### Linting tool alternatives

| Option | Pros | Cons |
|---|---|---|
| commitlint (recommended) | Standard, good messages, scope and footer rules, handles revert/fixup | Node tooling in a Java repo; type list is also in `cliff.toml` (duplicate, small) |
| Own regex script in `ci/` | No dependencies, one regex shared with the release script, in the style of `ci/smoke-test-image.sh` | We own the edge cases (revert, fixup, footers, multi-line) |
| git-cliff only | One tool and config for lint, changelog, version | Weak linting and weak messages |

## Part 2: Versions and CHANGELOG

* Lifecycle: `1.2.0-SNAPSHOT` (main) → `1.2.0-rc.1` → `1.2.0-rc.2` → `1.2.0` → `1.2.1-SNAPSHOT`. Lowercase `rc.N`
  (valid semver, valid Docker tag). Note: Maven sorts `-SNAPSHOT` *above* `-rc.N`, semver *below*. Irrelevant while only
  images are published; revisit if the jars ever go to a Maven repository.
* **One version in the POMs:** introduce `<revision>` (Maven CI-friendly versions, supported by Maven ≥ 3.5; modules use
  `<parent><version>${revision}</version>`). The release script then edits one line instead of 8 POMs. Add
  `flatten-maven-plugin` only when POMs are published. ADR-0010: the plugin version would be a root property.
* **Next version** = `git cliff --bumped-version` (git-cliff docs: `[bump]`):
  `features_always_bump_minor = true`, `breaking_always_bump_major = false` (so in 0.x a breaking change is a minor
  bump),   `no_increment_regex = "^(chore|ci|docs|test|build|refactor)$"` (open decision 4: whether `refactor` and `build`
  release on their own as a patch). Count from the last **final** tag (`--tag-pattern '^v[0-9]+\.[0-9]+\.[0-9]+$'`). The RC
  number is computed by the script: 1 + the number of existing `vX.Y.Z-rc.*` tags. If no commit bumps, the script
  refuses to release. `1.0.0` is a deliberate `--version 1.0.0`.
* **CHANGELOG** (**breaking-change handling is OPEN: [decision 5](#open-decisions)**): keep the current text as the
  hand-written baseline of `0.1.0` (rename the heading). From then on
  generate each **final** release's section with git-cliff (`--unreleased --tag vX.Y.Z --strip header`; the script
  inserts it above the first `## ` heading, because `--prepend` would put it above the intro). RCs do not touch the
  file; the GitHub pre-release notes come from `git cliff --unreleased`. Sections: `feat` → Added, `fix` → Fixed,
  `perf`/`refactor` → Changed, `BREAKING CHANGE` footer text verbatim → "Breaking changes". The maintainer may edit the
  generated section in the release PR; that is the reason to prefer a release PR over generating in the pipeline.
* **Verified 2026-11-17** (git-cliff 2.14.2, scratch repository, the `cliff.toml` below):
  * The generated file is fully automatic: `ci/release.sh prepare` runs git-cliff, nobody writes entries. Quality is
    the quality of the commit subjects and `BREAKING CHANGE:` footers, which is what Part 1 enforces. Subjects become
    user-facing lines, so they must read well on their own.
  * `tag_pattern` **must be anchored** (`^v…$`). Unanchored, `v0.2.0-rc.1` counts as a release boundary and
    `--bumped-version` answers `v0.2.0-rc.2`. Anchored, RC tags are ignored and the answer is `v0.2.0`.
  * With `conventional_commits` and `filter_unconventional`, history before the adoption (free-form subjects) is
    dropped from the output. A full regeneration from history therefore cannot recreate the old, hand-written
    entries: keep them as the baseline and add one generated section per release.
  * `--prepend` puts the section above the hand-written intro. Insert the output of
    `git cliff --unreleased --tag vX.Y.Z --strip header` above the first `## ` heading instead (an `awk` one-liner in
    the script worked).
  * The text of a `BREAKING CHANGE:` footer is available as `commit.breaking_description` and is printed under
    "Breaking changes".
  * Optional guard: a CI check that regenerates the newest section and fails if the release PR differs, so nobody
    edits it by hand. Drop it if hand-polishing the notes in the release PR is wanted.

## Part 3: Release process

### Approaches

| | A. Pipeline only (a `workflow_dispatch` commits version, changelog and tag) | **B. Local script + passive pipeline (chosen)** | C. Bot release PR (release-please) |
|---|---|---|---|
| Pros | One click; no local toolchain; same environment every time; hard to skip steps | The pipeline writes nothing and needs no write token for the repository; the release content is reviewed in a PR before it is tagged; runs and dry-runs locally; the changelog can be fixed by hand | A release PR is always open, the changelog is always current; merging releases; mature (has `maven`, `prerelease` strategies) |
| Cons | Pipeline pushes to `main` (PAT or ruleset bypass; `GITHUB_TOKEN` pushes do not trigger workflows); hard to reproduce locally; fights branch rules | Needs the maintainer's JDK 25, Maven, git-cliff; steps can be skipped by mistake; a human must run the tag step | Bot identity and PAT; RC and Maven handling is awkward; hand-editing the changelog is fiddly; less control |

Why B: the user's requirement is that the pipeline does not modify files; releases are rare and the maintainer group
is small, so a review gate is worth more than automation. The weakness of B (skipped steps) is covered by a pipeline
**gate** that re-verifies everything on the tag; CI never trusts a local run.

### Flow of B

1. `ci/release.sh prepare [--rc] [--version X.Y.Z]`: clean tree, on current `origin/main`, tools present
   (`git`, `git-cliff`, JDK 25, Maven ≥ 3.9). Computes the version (Part 2), creates `release/vX.Y.Z[-rc.N]`, runs
   `mvn -B verify` as a quick local check (CI is the authority; `VALIDATOR_JAR` optional), and makes **two commits**:
   1. `chore(release): X.Y.Z`: `<revision>` = `X.Y.Z`, CHANGELOG section (finals only).
   2. `chore(release): begin X.Y.(Z+1)-SNAPSHOT`: `<revision>` back to the next SNAPSHOT.
   Then it pushes the branch and opens the PR (`gh` if present, otherwise prints the URL).
2. The normal PR pipeline runs (`build`, `real-validator`, `image`, `commits`). The maintainer edits the changelog if
   needed and merges with **rebase-merge**, which keeps both commits.
3. `ci/release.sh tag`: on updated `main`, finds the `chore(release): X.Y.Z` commit, checks that its `<revision>` is
   `X.Y.Z`, creates the signed annotated tag `vX.Y.Z` **on that commit** and pushes it. The tag must be created
   **after** the merge: squash and rebase merges rewrite SHAs, so a tag made on the release branch would point at a
   commit that is not on `main`.
4. Pipeline on the tag (`image.yml`, already triggered): publishes both images, as today. Add:
   * a **gate** step in job `jar`: tag equals `<revision>`; no `SNAPSHOT`; for a final: `CHANGELOG.md` has a
     `## X.Y.Z` section; the tagged commit is reachable from `main`;
   * a **GitHub Release** job (`contents: write` for this job only): notes from the CHANGELOG section (RC: from
     `git cliff --unreleased`), `prerelease: true` for `-rc.N`, attaches the exec jar, the SBOM and SHA-256 sums.
5. Squash-only fallback (if rebase-merge is not allowed): the SNAPSHOT commit becomes a second small PR after the tag.

Files that name the version and how to treat them: `<revision>` (script), `CHANGELOG.md` (script), `docker-compose.yml`
(change the image to a version-free `testrunner:dev`, then it is not touched), `.claude/skills/testrunner-overview`
(reword without the version), ADR-0020 (reword), doc and `action.yml` examples `v0.1.0` (illustrative; update by hand
at the first release, say so in a comment).

## Open decisions

1. **Merge method:** squash-only (recommended; PR title is the commit) or also rebase-merge (needed by the single-PR
   release flow; otherwise the fallback in step 5)?
2. **0.x bumping:** does `feat` bump the minor version (assumed above) or the patch?
3. **Branch ruleset:** is blocking direct pushes to `main` and requiring the `commits` check acceptable? Without it
   nothing is enforced.
4. Do `refactor` and `build` commits count as a patch release, or do they never release on their own? (T3)
5. **OPEN, not decided yet (owner is unsure): how breaking changes are handled.** It blocks the final shape of T1,
   T3 and T4 (see the note under the task table). The pieces:
   * *What counts as breaking here.* This project has two kinds, and the current CHANGELOG keeps them apart:
     "Changed – may change results" (an existing TestScript passes or fails differently) and "Changed – API" (the
     public core API, guarded by `ArchitectureTest`). Both are breaking for users, but only the second is visible in
     a signature. A rule is needed that authors can apply, to be written into `docs/wiki/contributing.md`.
   * *How a commit says so.* `!` after the type, a `BREAKING CHANGE:` footer, or both. Whether commitlint should
     **require** the footer text when `!` is used (git-cliff prints that text as `breaking_description`; with `!` only,
     the entry has no explanation).
   * *Where the migration advice lives.* In the footer (one commit, one place, but footers are awkward to write and
     review), in the generated section edited by hand in the release PR (conflicts with "fully automatic" and with the
     optional guard in Part 2), or in a migration page that the footer links to.
   * *One heading or two.* Generated output has a single "Breaking changes" section. To keep "may change results" and
     "API" apart, a marker is needed: a scope (`api`), a footer token (e.g. `Affects: results`), or a different type
     of footer.
   * *Version effect.* In 0.x a breaking change bumps the minor version (decision 2); from 1.0.0 it bumps the major
     version, so the rule has to be settled before 1.0.0 at the latest.

## Tasks

Order: T1 and T2 are independent; T3 needs T2; T4 needs T3; T5 needs T2 to T4; T6 and T7 can follow each step.

**Open point: [decision 5](#open-decisions), breaking changes.** It affects T1 (commit rules and what commitlint
requires for `!`), T3 (the "Breaking changes" part of `cliff.toml` and the CHANGELOG headings) and T4 (whether the
release PR is hand-edited, and the guard). Settle it before starting those three; T2 does not depend on it. The task
list below is deliberately not changed yet.

| Id | Task | Done when |
|---|---|---|
| T0 | Settle the open decisions; set merge method and ruleset in the repository settings (manual, GitHub UI) | decisions written into the ADR; ruleset active |
| T1 | `commits.yml`, `commitlint.config.mjs`, Dependabot `commit-message` prefixes, `docs/wiki/contributing.md` (the commit rules, how to write a title) | a PR with a bad title or commit fails `commits`; a Dependabot PR passes |
| T2 | `<revision>` in the root POM, children use `${revision}`, `docker-compose.yml` image `testrunner:dev` | `mvn -B verify` is green; `mvn -Drevision=9.9.9 -DskipTests package` builds a `9.9.9` exec jar; `docker compose config` still works |
| T3 | `cliff.toml` (sections, bump settings, tag pattern); rename the CHANGELOG heading to the hand-written `0.1.0` baseline | `git cliff --bumped-version` and `--unreleased` print what is expected on a scratch tag |
| T4 | `ci/release.sh` (`prepare`, `tag`, `--rc`, `--version`, `--dry-run`); checks in the script have their own tests in the style of `ci/smoke-test-image.sh` (a scratch clone) | a dry run on a scratch clone produces the two commits and the right version for feat, fix, breaking, and RC after RC |
| T5 | Tag gate and GitHub Release job in `image.yml` | a test tag `v0.0.1-rc.1` on a fork or scratch repository publishes only `0.0.1-rc.1` (no `latest`, no `0.0`) and a pre-release; a tag that differs from `<revision>` fails |
| T6 | Rewrite `docs/wiki/ci-integration.md` › "Releasing (maintainers)", `docs/wiki/build-and-test.md` (CI section), ADR-0020 sentence about the POM version, new ADR-0021; update the skills `testrunner-build-and-run` (CI, `<revision>`) and `testrunner-overview` | docs and skills say the same as the code |
| T7 | Dry run: `0.1.0-rc.1`, then `0.1.0`; make the GHCR package public once (see ci-integration.md) | images, GitHub Release and CHANGELOG are as described |

### Sketches

`cliff.toml` was run with git-cliff 2.14.2 (see Part 2). Pin the git-cliff version in the script and in CI. The
commitlint config was not run.

```toml
# cliff.toml
[changelog]
header = """
# Changes

Generated from the commit messages (Conventional Commits) by git-cliff. Do not edit by hand.
"""
body = """
{% if version -%}
## {{ version | trim_start_matches(pat="v") }} ({{ timestamp | date(format="%Y-%m-%d") }})
{% else -%}
## Unreleased
{% endif -%}
{% set breaking = commits | filter(attribute="breaking", value=true) -%}
{% if breaking | length > 0 %}
### Breaking changes
{% for commit in breaking %}
* {% if commit.scope %}**{{ commit.scope }}:** {% endif %}{{ commit.message | upper_first }}{% if commit.breaking_description %}: {{ commit.breaking_description }}{% endif %}
{% endfor %}
{% endif -%}
{% for group, commits in commits | group_by(attribute="group") %}
### {{ group | striptags | trim | upper_first }}
{% for commit in commits -%}
* {% if commit.scope %}**{{ commit.scope }}:** {% endif %}{{ commit.message | upper_first }}
{% endfor %}
{% endfor %}
"""
trim = true

[git]
conventional_commits = true
filter_unconventional = true
tag_pattern = "^v[0-9]+\\.[0-9]+\\.[0-9]+$"   # finals only; anchored, see Part 2
sort_commits = "oldest"
commit_parsers = [
  { message = "^feat", group = "<!-- 0 -->Added" },       # the comment sorts the groups
  { message = "^fix", group = "<!-- 1 -->Fixed" },
  { message = "^(perf|refactor)", group = "<!-- 2 -->Changed" },
  { message = "^(docs|test|build|ci|chore)", skip = true },
]

[bump]
features_always_bump_minor = true
breaking_always_bump_major = false
initial_tag = "0.1.0"
```

```js
// commitlint.config.mjs
export default {
  extends: ['@commitlint/config-conventional'],
  rules: {
    'subject-case': [0],
    'body-max-line-length': [0],
    'scope-enum': [1, 'always', ['core', 'fhir-hapi', 'fhir-r4', 'fhir-r5', 'validation-cli',
      'autoconfigure', 'app', 'test-support', 'image', 'action', 'ci', 'docs', 'deps']],
  },
};
```

## References

* Conventional Commits 1.0.0 and SemVer 2.0.0 (`-rc.1` ordering: numeric identifiers compare as numbers).
* git-cliff: `--bumped-version`, `[bump]` (`features_always_bump_minor`, `breaking_always_bump_major`,
  `no_increment_regex`, `initial_tag`), `--tag-pattern`, `--unreleased`.
* release-please (approach C): `maven` strategy (SNAPSHOT after each release), `prerelease` versioning strategy,
  `bump-minor-pre-major`.
* `docker/metadata-action` `src/meta.ts`: semver pre-release gives only the exact tag, no `latest`.
* Existing: `.github/workflows/build.yml`, `image.yml`, `.github/dependabot.yml`, ADR-0010 (POM conventions), ADR-0020
  (published images, tags name the images, not the POM version).
