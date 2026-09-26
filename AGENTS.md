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
| `e2e/` | Playwright + TypeScript end-to-end harness (real `mvn`, `npm`, `cargo`, `docker`... clients against a running Repsy, plus a UI suite); not part of the Maven reactor. See `e2e/README.md` |

The root reactor (`pom.xml`) builds `repsy-backend`, `libs`, `repsy-protocols` and `repsy-frontend`.

## Related repositories

Repsy is spread over three repositories under the `repsyio` GitHub organisation:

| Repository | What lives there | How it relates to this one |
| --- | --- | --- |
| [`repsy`](https://github.com/repsyio/repsy) (this one) | The application: backend, frontend, protocol modules, scanner, e2e | |
| [`repsy-core`](https://github.com/repsyio/repsy-core) | Shared parent POM (`core-parent`: plugins, quality gates, Java/Maven enforcement), BOM (`core-bom`: dependency versions) and small shared libraries (`core-event`, `core-error-handling`, `core-response`, `core-uuidv7`) | Vendored here as the `core/` git submodule. See "Submodule" below |
| [`repsy-docs`](https://github.com/repsyio/repsy-docs) | The user documentation, published at [docs.repsy.io](https://docs.repsy.io) | Not vendored. It describes the behaviour users rely on (client setup per protocol, repositories, deploy tokens) |

- A change to how a build is configured, a dependency version or a shared library belongs in
  `repsy-core`, not here. Merge it there first, then bump the submodule.
- A change to user-visible behaviour (a protocol's client configuration, a panel feature, a
  documented setting or status code) needs a matching `repsy-docs` PR. Tests that pin documented
  behaviour name the docs issue they follow (for example `DeployTokenPasswordOnlyIT` cites
  `repsy-docs #42`), so read those before changing such a behaviour.
- `README.md` here covers installation, configuration and operation. Protocol usage guides live in
  `repsy-docs`, so link to them instead of copying them into `README.md`.

### Repsy OS and Repsy Cloud: one codebase, worked on in both directions

Repsy Cloud (the `repsy-cloud` application in the [`repsy-mono`](https://github.com/repsyio/repsy-mono)
repository, usually checked out at `../repsy-mono/repsy/repsy-cloud`) serves the same package
formats and panel API as this repository. Treat the two as **one codebase with two homes**: an
improvement made on either side belongs on the other too.

- **Look across before you finish.** When you fix a bug, tighten a validation, add an e2e case or
  write a better test helper here, check whether Cloud has the same defect or gap. If it does, apply
  the change there as well, or file a Jira story for it (search first) and name it in the PR. A
  protocol fix that lands on one side only is a divergence someone pays for later.
- **The reverse holds.** When Cloud has something better (a fix, a protocol edge case, an e2e
  scenario, a helper, a documented behaviour), bring it here instead of rediscovering it. A Cloud test
  that finds a bug is evidence about code Cloud shares with this repository, so it becomes a test or a
  ticket here.
- **Change shared contracts on both sides.** SPI changes in the protocol modules and `libs/`, and
  panel API changes, have to reach Cloud (the "Cloud sync" stories track them): do not change such a
  contract here and leave Cloud to find out.
- **Say so when a difference is deliberate.** Cloud-only concerns (hosted storage, tenancy) are not
  ported here, and OS-only ones (embedded H2, single-node install) are not forced onto Cloud. Note
  the reason in the code or the PR so a deliberate difference does not read as drift.
- **The repositories stay separate.** A change lands through the pull request flow of the repository
  it belongs to, under that repository's review and merge rules. Do not merge in the other repository
  without the owner's go-ahead.

## Architecture

### Runtime shape

One Spring Boot process (`RepsyApplication`) serves two HTTP ports, set up by the `multiport`
library (`libs/multiport`, `@EnableMultiport`, `@RestApiPort`):

| Port | Default | Serves |
| --- | --- | --- |
| `api` | 8080 (`API_PORT`) | The panel REST API (`/api/...`) and the Angular single-page app (`spa/`) |
| main | 9090 (`SERVER_PORT`) | The package-format wire protocols (`mvn deploy`, `npm publish`, `docker push`...) |

Optional HTTPS listeners sit beside them (8443 aliased to `api`, 9443 to the main port); HTTP is never
turned off. The frontend is built into the same image and served by the backend, so a plain install
is one container plus, optionally, PostgreSQL (embedded H2 is the default) and the scanner service.

### Two request paths

- **Panel (`api` port).** Angular calls the REST API implemented in `repsy-backend/.../panel/` (`auth`,
  `profile`) and in the `ui/` packages of each protocol and of `server/security/scan/`. The contract
  is `openapi-spec.yaml` (see "API spec").
- **Protocol (main port).** `ProtocolRouterController` (`libs/protocol-router`) is a catch-all
  `@RequestMapping("/**")`. Each package format contributes a `ProtocolProvider` and a set of
  `ProtocolMethodHandler`s, each with a `PathParser`. The router asks the parsers of the handlers
  registered for the HTTP method, the first one that recognises the URL builds the `ProtocolContext`,
  and that handler serves the request. Pre-processors (for example `MavenAuthPreProcessor`)
  authenticate the caller before the handler runs. Post-processors (`ProtocolProcessor`s ordered by
  priority) run after it, for example `UsagePostProcessor` and `ArtifactPushedEventPostProcessor`;
  by default they are skipped when the handler failed unless they opt in with `runsOnFailure()`.

### Module layout

- **`libs/`** has no Repsy domain knowledge: `protocol-router` (the routing above), `multiport`
  (several Tomcat connectors and per-port controller mapping), `storage`
  (`storage-gateway` defines `StorageStrategy`, a path-based API where deletes are soft and
  recoverable until the trash is cleared; `storage-gateway-fs` is the filesystem implementation).
- **`repsy-protocols/<format>`** (`maven`, `npm`, `pypi`, `docker`, `cargo`, `golang`, `helm`,
  `nuget`, `ruby`) holds the format's wire-protocol logic that does not depend on Repsy's database:
  the `ProtocolProvider`, abstract handlers and facades, contracts (interfaces) the backend must
  implement, and format parsing and storage utilities. `repsy-protocols/shared` is what all of them
  use (`RepoType`, `Permission`, `Credentials`, bounded upload readers, digest helpers).
- **`repsy-backend/.../server/protocols/<format>`** implements those contracts on top of the
  entities, repositories and services, and is split the same way in each format:
  `protocol/` (concrete handlers, facades, path parser, pre-processors), `shared/`
  (entities, auth, listeners, storage code that the `protocol` and `ui` parts both use) and `ui/`
  (the panel controllers for that format). To add a format, add a module under `repsy-protocols/`, the
  matching package here, an `ArtifactStorageResolver` for scanning if it can be scanned, and a
  `RepoType`.
- **`repsy-backend/.../shared/`** holds what the panel and the protocols share: users and
  authentication, repos, deploy tokens, usage, paging, error handling, security headers and CORS.
- **`server/core`, `server/shared`** hold the cross-protocol server plumbing (URL parsing, auth,
  tokens, usage post-processors).

### Module boundaries

The backend is a Spring Modulith application. Each top-level package with a `package-info.java`
annotated `@ApplicationModule` is a module (`panel.auth`, `panel.profile`, each protocol's
`protocol`, `shared` and `ui`, and so on), and packages marked `Type.OPEN` (`shared`, `server.core`,
`server.shared`, `server.security`, each protocol's `shared`) may be used by any module. Anything
else should not reach into another module's internals. Modules talk through Spring events, for
example an `ArtifactPushedEvent` that starts a vulnerability scan. The `spring-modulith-starter-test`
and ArchUnit dependencies are on the backend's classpath, but no test verifies the module structure
yet, so nothing fails the build if a boundary is crossed: keep to it by hand, and give shared code
an OPEN `shared` home or an event rather than a direct dependency on another module's internals.

### Data and storage

- **Metadata** (users, repos, artifacts, versions, tokens, scans) lives in PostgreSQL or embedded
  H2 through Spring Data JPA. Flyway owns the schema, once per dialect (`db/migration/postgresql`,
  `db/migration/h2`); see "Database".
- **Artifact bytes** live behind `StorageStrategy`, namespaced by repo UUID. A publish must write
  its metadata rows and its files as one unit, so a failed one leaves neither behind.
- **Authentication.** The panel uses a JWT access token (`Authorization: Bearer`, from `login` and
  `refreshToken`). Protocol clients authenticate with account credentials or deploy tokens.

### Vulnerability scanning

Optional and off by default (`SECURITY_SCANNER=disabled`). `server/security/` defines
`VulnerabilityScanner` with a no-op and a Trivy implementation. After a publish,
`ArtifactScanListener` (an `@EventListener` that runs the scan on the `scanTaskExecutor` pool)
resolves the artifact's files
through the format's `ArtifactStorageResolver`, sends them to `repsy-scanner-trivy` (a separate
service with `POST /scan`, guarded by an API key) and `TrivyScanStatusPoller` collects the result.
Findings are stored per repo and surfaced in the panel and in `npm audit`.

### Frontend

`repsy-frontend/` is an Angular app (standalone components, `src/app/{auth,panel,shared}`). Its API
client is generated from `openapi-spec.yaml` into `src/generated/api` (git-ignored) with `pnpm gen:api`.

### Tests at three levels

`*Test` (unit, no Docker) and `*IT` (Testcontainers, see "Testing") live with the Maven modules;
`e2e/` drives real package-manager clients and the panel UI against a running stack and is where a
protocol is proven against the real tool. It has its own README, runners and `run.sh`.

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

`mvn verify` runs the integration tests of `repsy-backend` (nearly all the time it takes) in
several test JVMs at once: `it.fork.count` of them, 4 by default. Each JVM starts its own
`postgres:18` container and storage root (see "Testing"), so they never share a database. A JVM
needs about 1.5 GB and one CPU core to itself; set `-Dit.fork.count=` to the number of cores you
can spare, and `-Dit.fork.count=1` to run them one after the other (for example to look for an
order-dependent failure). `-T 1C` also builds independent modules in parallel.

`mvn verify` (what CI runs) covers unit tests, integration tests, `fmt-maven-plugin`
(Google Java Format), Checkstyle, SpotBugs, Apache RAT and Error Prone. Run
`mvn com.spotify.fmt:fmt-maven-plugin:format` if it reports formatting violations.

The JaCoCo 80% coverage gate from `core-parent` is opt-in per module here
(`jacoco.check.phase` is `none` in the root `pom.xml`). Coverage reports are still generated
for SonarCloud.

## Testing

- **Unit tests** are named `*Test` and run in Surefire (`mvn test`). They must not need Docker.
- **Integration tests** are named `*IT` and run in Failsafe (`mvn verify`, or the one-class command
  below). They need a running Docker daemon. Never give an integration test a `*Test` name, or it
  will run in `mvn test`.
- **Faster edit-test loop.** `mvn -T 1C test` runs the unit tests of all modules with no Docker in
  about a minute. For one integration test class:

  ```bash
  mvn verify -T 1C -pl repsy-backend -am -Dit.test=ProfileControllerIT \
    -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dfailsafe.failIfNoSpecifiedTests=false \
    -Dfmt.skip -Dcheckstyle.skip -Drat.skip
  ```

  It builds the upstream modules without running their tests and takes about 1.5 minutes on a
  24-thread workstation, most of it compiling; add `-o` when nothing has to be downloaded. Separate
  several classes with commas (`-Dit.test='AIT,BIT'`): a `+` matches nothing and still ends in
  `BUILD SUCCESS`, so look for `-- in ...IT` lines. The full `mvn -T 1C verify` takes about 5 to 6
  minutes there (12 to 13 before RPS-1453).
- Integration tests use Testcontainers with **PostgreSQL 18** (`postgres:18`), wired in through
  `@ServiceConnection`. Keep it on the same major version as the images documented in
  `README.md`.
- Every `*IT` extends `AbstractIntegrationTest` and shares that one PostgreSQL container. The
  container is per test JVM: `mvn verify` runs `it.fork.count` JVMs at once (see "Build & verify"),
  each with its own container, storage root and Spring contexts, and Failsafe hands the classes out
  to them one by one. So a class only ever shares a database with the classes of its own JVM, and a
  test must not rely on any other class having run, or not run, in the same JVM. Most tests
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
- A BCrypt hash costs about 100 ms of CPU, and hashing per test was about 60% of the CPU of a full
  integration run (RPS-1453). `createUser` and the `*BearerToken()` helpers reuse
  `VALID_PASSWORD_HASH`, the hash of `VALID_PASSWORD` made once per JVM; use it (not
  `PasswordHasher.hash(VALID_PASSWORD)`) whenever a test only needs a user that can log in with the
  known password, and hash on your own only for a different password or a specific work factor.
  `mvn verify` runs the forked test JVMs with C1 only (`test.jvm.args` in the root `pom.xml`): see
  the comment there before touching `argLine`.
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

## Keeping the pnpm pin up to date

The pnpm version is a literal in `.github/actions/setup-frontend/action.yml`, the `Dockerfile` and every
`e2e/runners/*.Dockerfile` (`DockerfileTest` keeps them equal). Dependabot cannot track a literal in a
`run:` line, so `.github/workflows/pnpm-bump.yml` runs weekly, rewrites all of them to the newest release
of the pinned major in one commit and opens a PR that is queued with `gh pr merge --auto --squash`. A new
major is left to a person.

The PR is opened with a GitHub App token, because a PR opened with `GITHUB_TOKEN` triggers no
`pull_request` workflows and the merge queue would wait for checks that never report. One-time setup
(repository admin): create a GitHub App with repository permissions Contents: write, Pull requests:
write and Metadata: read, install it on `repsyio/repsy`, then store its ID as the Actions variable
`PNPM_BUMP_APP_ID` and its private key as the secret `PNPM_BUMP_APP_PRIVATE_KEY`. Until then the workflow
only writes a note to its run summary. Trigger it once with `gh workflow run pnpm-bump.yml`.

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
- A Docker manifest (`docker_manifest`) is content-addressed: one row per image and `sha256` digest,
  its file at `<repoUuid>/manifests/<digest>` (shared by the images of a repo, deleted only when no row
  of the repo has the digest), and a tag (`docker_tag`) is a pointer to it. A migration that changes
  populated data is tested on legacy data at the previous version: see `DockerManifestMigrationScenario`
  (`V0024DockerContentAddressedManifestsTest` on H2, `DockerManifestMigrationIT` on PostgreSQL, which
  owns its container instead of extending `AbstractIntegrationTest`).
- A Docker image (`docker_image`) exists while it stores a manifest. A manifest push creates it in the
  same transaction that saves the manifest (`ImageTxService.findOrCreateImage`, an insert that skips an
  existing row), so a push that fails leaves no image behind; what the tags and untagged manifests reach
  is one recursive walk over the index edges, in `UntaggedManifestFinder` and in the recursive CTEs of
  `ImageRepository` and `LayerRepository`, and they must agree (`DockerUntaggedManifestCleanupIT`).
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

repsy-core releases are tags only (no published artifacts, RPS-1085). After a core release, bump
`<parent><version>` in `pom.xml` together with the pointer.
