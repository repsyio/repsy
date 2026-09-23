# AGENTS.md

Guidance for AI coding agents working in this repository.

## What this repository is

Repsy is an open-source universal package repository (Maven, npm, PyPI, Docker, Cargo, Go, Helm,
NuGet, Ruby). It is a Spring Boot 4 backend plus an Angular frontend. See `README.md` for
installation, configuration and usage.

## Modules

| Path | Purpose |
| --- | --- |
| `core/` | Git submodule ([`repsy-core`](https://github.com/repsyio/repsy-core)): shared parent POM, BOM and libraries. It is the Maven parent of the root `pom.xml` |
| `repsy-backend/` | Spring Boot application (`io.repsy.os`): `panel/` (web UI API, e.g. `profile`, `auth`), `server/` (repository serving), `spa/`, `config/` |
| `libs/` | Shared libraries: `protocol-router`, `multiport`, `storage` |
| `repsy-protocols/` | One module per package format, plus `shared` |
| `repsy-frontend/` | Angular app (pnpm) |
| `repsy-scanner-trivy/` | Optional standalone vulnerability-scanner service; not part of the root Maven reactor |

## Requirements

- Java 25 and Maven 3.9.7+ (enforced by `core-parent`)
- Docker daemon, for integration tests (Testcontainers) and for building images
- Node.js 24.x and pnpm, for `repsy-frontend/`

## Build & verify

Dependency versions, plugin configuration and quality gates come from `repsy-core`, so initialise
the submodule and install it once before building this repo:

```bash
git submodule update --init
mvn install -f core/pom.xml -DskipTests -Drat.skip=true -Dcheckstyle.skip=true
mvn verify
```

Re-run the `install -f core/pom.xml` step after the `core` submodule pointer moves.

`mvn verify` (what CI runs) covers unit tests, integration tests, `fmt-maven-plugin`
(Google Java Format), Checkstyle, SpotBugs, Apache RAT and Error Prone. Run
`mvn com.spotify.fmt:fmt-maven-plugin:format` if it reports formatting violations.

The JaCoCo 80% coverage gate from `core-parent` is opt-in per module here
(`jacoco.check.phase` is `none` in the root `pom.xml`). Coverage reports are still generated
for SonarCloud.

## Testing

- **Unit tests** are named `*Test` and run in Surefire (`mvn test`). They must not need Docker.
- **Integration tests** are named `*IT` and run in Failsafe (`mvn verify`, or
  `mvn verify -pl repsy-backend -am -Dit.test=ProfileControllerIT` for one class). They need a
  running Docker daemon. Never give an integration test a `*Test` name, or it will run in
  `mvn test`.
- Integration tests use Testcontainers with **PostgreSQL 18** (`postgres:18`), wired in through
  `@ServiceConnection`. Keep it on the same major version as the images documented in
  `README.md`.
- Every `*IT` extends `AbstractIntegrationTest` and shares that one PostgreSQL container. Most tests
  run in a transaction that is rolled back. A class that must commit (an `@Async` listener cannot
  see an open transaction) uses `@Transactional(propagation = Propagation.NOT_SUPPORTED)`, tracks
  the rows it creates and deletes exactly those in an `@AfterEach`. It never empties a table, and
  it measures a baseline instead of asserting the absolute size of a table that other classes
  fill. `DefaultRepoSeedingIT` and the H2 suites own their database and are the exceptions.
- `CommittedRowsGuard` (registered on `AbstractIntegrationTest`) enforces that. It snapshots the row
  count of every table once the default repos are seeded, and after each class it fails the class
  whose counts differ, naming the class and the tables. It also deletes the `users` and `repo` rows
  the class added, so the next class starts clean and the failure stays with the class that
  caused it. When it fails a class, add the missing cleanup to that class; do not loosen the guard.
- The order of the IT classes is deliberately not fixed. The guard makes it irrelevant for leaked
  rows, so don't write a test that only passes because another class ran (or didn't run) before it.
- Do not declare versions for `org.testcontainers:*` artifacts: they are managed by `core-parent`
  (`testcontainers-bom`). The same goes for any other dependency `repsy-core` already manages,
  so only add a `<version>` for something it does not.
- Spring Boot 4 splits test support into per-technology modules: `@AutoConfigureMockMvc` comes
  from `spring-boot-starter-webmvc-test`, and `@ServiceConnection` from `spring-boot-testcontainers`.

## Code style

- Google Java Style, enforced by `fmt-maven-plugin` and Checkstyle at `verify`.
- Lombok and MapStruct annotation processors are available via `core-parent`.
- New source files need the Apache 2.0 licence header (see any existing file), or RAT fails
  the build.
- Frontend: follow the Angular Style Guide and run `pnpm lint` in `repsy-frontend/`.
- Commit messages: conventional commits, prefixed with the Jira key where there is one
  (for example `RPS-844: ...`).

## Merging to `main`

Two PRs can each pass CI against an older `main`, merge without a textual conflict, and still break
the build together (RPS-901 and RPS-904 did: an unused import failed Checkstyle on `main` and then
on every open PR). To rule that out, `main` is merged through a **GitHub merge queue**. The queue
builds each PR on top of the entries ahead of it and merges it only if that combination is green.

- Enqueue a PR with `gh pr merge <n> --auto --squash` (or the "Merge when ready" button). Do not
  update the branch by hand before merging: the queue already tests it against the latest `main`.
- `.github/workflows/pr-checks.yml` runs on `pull_request` and on `merge_group`, so every check
  below reports on both. A workflow that a required check comes from must keep the `merge_group`
  trigger, or queued PRs never get a result and time out.
- Required checks in the `protect default` ruleset: `Repsy check`, `Repsy frontend` and
  `Editorconfig check - All`. Add a new job to that list when it should gate merges.
- Queue settings: squash merge, `ALLGREEN` grouping, up to 5 entries built at once, 60 minutes
  to report checks.
- `gh pr merge --admin` skips the queue and the required checks. Org admins keep that bypass as a
  break-glass for a red `main` or a stuck queue only. A PR merged that way is not re-verified
  against the other open PRs, so do not use it for routine merges.
- If `main` still goes red, fix it with a PR of its own (as RPS-960 did) rather than folding the
  fix into an unrelated PR.

## API spec

`repsy-backend/src/main/resources/openapi/openapi-spec.yaml` is the single source of truth for the
panel API. Edit that file for any API change; there is no other copy. Both sides are generated from it:

- Backend DTOs: `openapi-generator-maven-plugin` in `repsy-backend/pom.xml` writes them to
  `target/generated-sources/openapi` during the Maven build.
- Frontend client: `pnpm gen:api` in `repsy-frontend/` writes `src/generated/api`, which is git-ignored.
  Re-run it after the spec changes. The `Dockerfile` runs the same generator.

## Database

- PostgreSQL 18 is the supported production database; embedded H2 is also supported (see
  `README.md`).
- Schema is managed by Flyway in `repsy-backend/src/main/resources/db/migration/` (PostgreSQL
  scripts under `postgresql/`), named `V{version}__{description}.sql`. Add a new migration rather
  than editing an existing one.
- Use `postgres:18` in any Dockerfile, compose file, README snippet or test you add.
- Hibernate does not validate the entity mappings (`ddl-auto: none`), so
  `EntitySchemaAnnotationIT` and `H2EntitySchemaAnnotationIT` compare every entity of the
  metamodel with `information_schema` (`EntityColumnSchemaChecks`). A new entity or column is
  checked without being listed. A `varchar` column carries exactly its `length` (in H2 at most its
  length); a column that is `NOT NULL` says `nullable = false` (an id or a primitive needs no
  declaration) and one that allows null never does.
- One convention for an unbounded (`text`) `String` column: `@Column(name = "...",
  columnDefinition = "text")`, with no `length`. Do not use `columnDefinition = "clob"` (that is
  only what H2 makes of it) and do not add `@Lob`: it has no place in a mapping that only documents
  the schema and it changes how Hibernate binds the value. The guard enforces this. A column that
  is `text` in PostgreSQL but `varchar(n)` in H2 is listed in `EntityColumnSchemaChecks`
  (`H2_BOUNDED_TEXT`), so that difference is recorded rather than discovered by an insert.

## Submodule

`core/` is pinned to a specific `repsy-core` commit. Bump it only as a deliberate change (its own
PR, or a clearly called-out part of one), and re-run the core install above afterwards. Pin it
only to a commit that is on `repsy-core`'s `main`, so merge the `repsy-core` PR first: a commit
that only exists on a PR branch disappears from the remote when that branch is deleted on merge.
`repsy-core` merges to `main` through the same merge queue flow described above.
