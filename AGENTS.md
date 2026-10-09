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
- **The e2e UI suite is shared: write specs for both targets.** Cloud runs `e2e/tests/ui` verbatim
  against a multi-tenant stack, where panel API and file URLs carry the owner
  (`/api/repos/<owner>/<repo>/...`, `/<owner>/<repo>/<path>`) while OS has a single owner and omits it.
  A spec or page object must never hard-code either shape: build URLs through the target helpers
  (`repoRoute`, `repoPath`/`repoUrl`, `target.urlScheme`), whose OS implementation returns today's
  owner-less strings. Do not widen a matcher into an owner-tolerant regex to make a test pass on
  Cloud; that hides a wrong or missing owner. If no helper exists for the URL you need (the panel
  API path is the known gap), add one to the target layer instead of inlining the path.
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
Findings are stored per repo and surfaced in the panel and in `npm audit`. `npm audit` also asks the
scanner's `POST /advisories` (`VulnerabilityAdvisoryLookup`, RPS-1612) for the name/version pairs the
client sends and merges the answer with the stored findings; any non-200, timeout or failure of that
lookup means "no lookup this time" and the audit is answered from the stored findings.

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
order-dependent failure). Do not add `-T` to `mvn verify`: `apache-rat-plugin` 0.18 is not
thread-safe and fails a parallel reactor with a `ConcurrentModificationException` (CI does not use
it either). `-T 1C` is fine for `mvn test`, which does not run RAT.

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
  `BUILD SUCCESS`, so look for `-- in ...IT` lines. The full `mvn verify` takes about 5 to 6
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
  known password, and hash on your own only for a different password or a specific work factor, once
  per class in a `private static final` field. `IntegrationTestHashingGuardTest` (a unit test, so
  `mvn test` runs it) fails an integration test or helper that calls `PasswordHasher.hash` per test
  or hashes `VALID_PASSWORD` again, and names the file, the line and the fix; a test that needs a
  fresh hash per call goes in its `ALLOWED` map with the reason (RPS-1471).
  `mvn verify` runs the forked test JVMs with C1 only (`test.jvm.args` in the root `pom.xml`, which
  `core-parent` appends to the Surefire and Failsafe `argLine`): see the comment there, and never set
  the `argLine` property itself.
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

## Java naming

The naming rules of Repsy OS and Repsy Cloud (RPS-2009). They are the target: some existing code still
breaks them, and Jira stories under RPS-2009 migrate it. Do not add new exceptions. Cloud's
`repsy-cloud-server` copies the OS backend classes, so a name changed here is changed in Cloud after the
submodule bump: rename in this repository first. The same rules are in `repsy-mono`'s `AGENTS.md`; change
them in both repositories together.

### Types

- `PascalCase` with a role suffix: `Controller`, `Service`, `Repository`, `Facade`, `Listener`, `Handler`,
  `Resolver`, `Parser`, `Processor`, `Interceptor`, `Task` (scheduled), `Exception`. Settings are
  `*Properties` (`@ConfigurationProperties`) and `*Config` (`@Configuration`), never `*ConfigProps` or
  `*Configuration`. Static helper classes are `*Utils`, never `*Util`.
- Abbreviations are written as words: `Pgp`, `Url`, `Otp`, `OAuth`, `NuGet`, `Go` (not `PGP`, `URL`, `OTP`,
  `Oauth`, `Nuget`, `Golang`). The `golang` package keeps its name.
- Protocol handlers are `<Format><Operation>ProtocolMethodHandler`, bases are `Abstract<...>`.
- DTO roles: `Info` (detail or response), `Item` (list row), `Form` (request body), `Payload` (wire payload).
  `Request` and `Response` are for calls to and from external HTTP services. No `Dto`, `DTO` or `Model`
  suffix. New DTOs are records.
- MapStruct mappers are `*Mapper`, in a `mappers` package (not `*Converter`).
- No `Impl` suffix at all. A single implementation is a concrete class without an interface. A class that
  implements a library contract (an interface in `repsy-protocols` or the libs, implemented once per
  product) takes the contract's plain name and extends the library `Abstract<Contract>` base (the same-name
  pair rule below). A second implementation of the same thing gets a role name (`NoOp<Name>`,
  `Trivy<Name>`), not `Impl`. The only exception, until the owner decides, is a class that must extend a
  backend base class and so cannot extend a library `Abstract` class: `NpmAuthenticatorImpl` (it extends
  `ProtocolAuthService` and implements three library interfaces).
- **`*TxService`** (and `<Format>ProtocolTxFacade`) is the transactional persistence layer below a service or
  facade. It is the only place for the `Tx` infix; do not add it elsewhere.
- **Library interface and backend class share a name.** Each format splits into a library
  (`io.repsy.protocols.<format>`: `<Format>ProtocolFacade` and `<Format>StorageService` interfaces and the
  service contracts such as `ArtifactService`, `NpmPackageService` or `PypiPackageService`, with `Abstract*`
  bases) and the backend (`io.repsy.os.server.protocols.<format>`: the concrete class of the same simple name).
  The backend class extends the library `Abstract<...>` class, never implements the interface directly, has
  only a constructor, and callers inject the interface. This pattern is deliberate; do not prefix the backend
  classes.
- One simple class name per concept. When two formats need the same word, prefix the format
  (`NpmPackageUtils`, `PypiPackageListItem`). The library/backend pair above is the only allowed repeat.
- Scanner classes copied between `repsy-scanner-trivy` and the backend are named after their side; the wire
  records keep their names, because the HTTP and JSON contract is pinned by `e2e/src/stubs/scanner/contract.ts`.
  There is no shared scanner module.

### Methods and fields

- `get*` returns the value or throws not found. `find*` returns `Optional` or a collection. `fetch*` and
  `tryFetch*` call a remote service. `require*` throws and returns the resource.
- `check*` and `validate*` throw and return nothing. `verify*` is a cryptographic or one-time-code check that
  returns a boolean. `try*` returns whether it happened.
- `create`, `update`, `delete` act on rows (not `remove`); `clear` and `evict` act on caches; `purge` is a
  retention sweep. Boolean methods read as predicates (`is*`, `has*`, `can*`, `exists*`).
- Listener methods are `on<Event>`.
- Fields are `camelCase`. Boolean fields have no `is` prefix. Identifiers are `xxxId` (`repoId`, `tokenId`),
  not `xxxUuid`.
- Config keys are kebab-case under `repsy.<area>.<key>`, bound with `@ConfigurationProperties`; use `@Value`
  only for a single key. Environment variable names (`STORAGE_BASE_PATH` and the others in `README.md`) are a
  user contract and are never renamed.
- A column name is the lowercase snake case of its field name; a mapping that differs says why.

### Constants and error codes

- Constants are `UPPER_SNAKE_CASE`, in a `*Constants` class in a `constants` package.
- Error codes (`msgId`, later `code`) are `camelCase` strings and are a client contract: never rename or reuse
  one. Declare a new one in the constants holder, not as an inline literal.

### Packages and modules

- Package names are lowercase; an underscore between words is allowed (`go_module`, `pre_processors`,
  `error_handling`) and is not renamed (RPS-2019).
- Layer packages are plural and hold only classes with that role: `services`, `controllers`, `repositories`,
  `dtos`, `entities`, `mappers`, `listeners`, `configs`, `constants`, `utils`, `contracts`. A scheduled task
  is in `tasks`, not `services`.
- A Maven module's directory equals its `artifactId`; its `groupId` equals the root package and has no
  hyphen.

### Tests

- `*Test` is a unit test, `*IT` an integration test (see "Testing"); abstract IT bases are `Abstract*IT`.
- Test method names are a camelCase sentence about the behaviour, with no `should` or `test` prefix and no
  underscores; `@DisplayName` carries the readable sentence.

## Merging to `main`

Two PRs can each pass CI against an older `main`, merge without a textual conflict, and still break
the build together (RPS-901 and RPS-904 did: an unused import failed Checkstyle on `main` and then
on every open PR). There is no merge queue (removed in RPS-1849): a PR merges once its required checks are green, so
rebase or update the branch when another PR has just changed the same area, and watch `main` after a merge.

- Merge a PR with `gh pr merge <n> --auto --squash` (or the "Merge when ready" button): it squashes
  once the required checks are green. `.github/workflows/pr-checks.yml` runs on `pull_request`.
- Required checks in the `protect default` ruleset: `Java check - All`,
  `Node check - All`, `Editorconfig check - All` and `PR title` (`pr-title.yml`). The names match
  repsy-mono. Add a new job to that list when it should gate merges.
- Every PR title reads `RPS-1234: Description`, or `RPS-1, RPS-2: Description` for several
  tickets. `pr-title.yml` checks it; Dependabot PRs are exempt. The squash commit takes the title.
- `gh pr merge --admin` skips the required checks. Org admins keep that bypass as a
  break-glass for a red `main` only. A PR merged that way is not re-verified
  against the other open PRs, so do not use it for routine merges.
- If `main` still goes red, fix it with a PR of its own (as RPS-960 did) rather than folding the
  fix into an unrelated PR.

## Dependabot and code scanning

- SonarCloud (PR analysis in `pr-checks.yml`; `main` is analysed by `sonarcloud.yml` at 10, 12, 14, 16 and 18 o'clock Europe/Amsterdam, not on every merge) is the only code scanner. There is no CodeQL workflow and GitHub code scanning is not configured; do not add them back (RPS-1849).
- `.github/dependabot.yml` checks daily, except the `gitsubmodule` entry, which also checks daily (Dependabot allows no interval shorter than 24 hours, so `interval: "cron"` with an hourly expression is rejected). Minor and patch updates share one PR per update entry (group `minor-and-patch`); a major update gets its own PR. Every entry keeps `open-pull-requests-limit: 3`.
- The same rules apply in `repsy-core` and `repsy-mono`; change them in all three repositories together.


## Keeping the pnpm pin up to date

The pnpm version is a literal in `.github/actions/setup-pnpm/action.yml`, the `Dockerfile` and every
`e2e/runners/*.Dockerfile` (`DockerfileTest` keeps them equal). Dependabot cannot track a literal in a
`run:` line, so `.github/workflows/pnpm-bump.yml` runs weekly, rewrites all of them to the newest release
of the pinned major in one commit and opens a PR that auto-merges with `gh pr merge --auto --squash`. A new
major is left to a person.

The PR is opened with a GitHub App token, because a PR opened with `GITHUB_TOKEN` triggers no
`pull_request` workflows and auto-merge would wait for checks that never report. One-time setup
(repository admin): create a GitHub App with repository permissions Contents: write, Pull requests:
write and Metadata: read, install it on `repsyio/repsy`, then store its ID as the Actions variable
`PNPM_BUMP_APP_ID` and its private key as the secret `PNPM_BUMP_APP_PRIVATE_KEY`. Until then the workflow
only writes a note to its run summary. Trigger it once with `gh workflow run pnpm-bump.yml`.

## API guideline

This is the shared contract of the **Repsy OS panel API** and the **Repsy Cloud panel API** (the routes the web
panel calls; the wire protocols of the package formats are not covered). It is the same text as the "API guideline"
section of `repsy-mono`'s `AGENTS.md`, adapted to the single tenant OS: read OS paths without `{repoOwner}`. When a
rule changes, change it in both repositories together. Every API change follows it.

### Principle

- OS is single tenant and Cloud is multi tenant. The OS spec is the reference. A Cloud path is the OS path with
  `{repoOwner}` inserted before `{repoName}` (OS `/api/repos/{repoName}/settings`, Cloud
  `/api/repos/{repoOwner}/{repoName}/settings`). Everything after the repo segment is identical: verbs, parameter
  names, request and response schemas, status codes, error codes.
- Tenant-only features (billing, subscriptions, coupons, custom domains, public profiles, MFA, OAuth, webhooks, proxy
  repos, support) exist only in Cloud; OS has users instead of tenants. Do not port them to OS.
- Breaking changes are allowed if this guideline is followed and every client (the panel frontend, e2e, generated API
  clients) is updated in the same PR.
- Where the spec lives: `repsy-backend/src/main/resources/openapi/openapi-spec.yaml` (OpenAPI 3.1, `Repsy Panel API`,
  hand written). The Cloud spec lives in `repsy-mono`; this repository owns every schema both products share
  (`PagedModel`, `RepoType`, the problem+json error, per-format list items, scan findings). `repsy-core` is also used
  by other projects, so no Repsy API spec or fragment lives in it.

### URL shape

- Repo-generic routes: `/api/repos/{repoName}/...`. Per-format routes: `/api/{format}/{plural}/{repoName}/...`
  (`cargo/crates`, `docker/images`, `go/modules`, `helm/charts`, `mvn/artifacts`, `npm/packages`, `nuget/packages`,
  `pypi/packages`, `ruby/gems`).
- Paths are lowercase kebab-case nouns; verbs go in the HTTP method. An action that is not CRUD is
  `POST .../actions/{kebab-name}` (`deploy-tokens/{tokenId}/actions/rotate`, `users/{userId}/actions/reset-password`).
- **A literal segment never sits at a level where `{repoOwner}` can appear in Cloud**, because a tenant may be named
  like the literal. Put the repo first and the literal after it (`/api/repos/{repoName}/cache/browse`); a literal is
  fine as the first segment after `/api/` and after a complete repo pair (`/settings`, `/deploy-tokens`).
- Path variable names are the same in both APIs and in every format: `{repoName}`, `{packageName}` (the unit that holds
  versions: crate, chart, npm, NuGet or PyPI package, gem), `{imageName}`, `{groupName}` and `{artifactName}` (Maven),
  `{version}`, `{tagName}`, `{digest}`, `{reference}`, `{userId}`, `{tokenId}`, `{scanId}`, `{keyStoreId}`. Ids are
  always `{xxxId}`, never `{xxxUuid}`.
- **Version sub-resource for every format**: `/{packageName}/versions` (list) and `/{packageName}/versions/{version}`
  (detail, delete).
- The panel API addresses repos and packages by name, not by database id.
- **Scoped npm names, one encoding**: the scope is its own path segment without the `@`, and scoped packages live in
  the scopes tree. Unscoped: `/api/npm/packages/{repoName}/{packageName}[/versions/{version}|/tags]`. Scoped:
  `/api/npm/scopes/{repoName}/{scope}/packages/{packageName}[/versions/{version}|/tags]`. The scope listing is
  `/api/npm/scopes/{repoName}/packages` and `/api/npm/scopes/{repoName}/{scope}/packages`. The npm wire protocol
  (`@scope%2Fname` in the registry URL) is unaffected.
- **Docker names with several segments** (`team/app`) use an `image` query parameter on the nested image routes;
  `{imageName}` stays for single segment names. Encoded slashes are not enabled, because that weakens path traversal
  protection.

### Search, list and detail

- The one search parameter is `q` (case-insensitive contains). Filters that are not free text keep their own names
  (`type`, `severity`, `platform`, `repoNames`, `modulePath`, `path`).
- One list shape, one detail shape and one search shape per format: every list is `GET .../{plural}/{repoName}`
  returning the `PagedModel` of that format's list item; every detail is `GET .../{packageName}`; search is `q` on the
  list, never a separate `/search` route.
- Pagination (enforced by `PagingParameterInterceptor`): `page` zero based, default 0; `size` 1 to 100, default 10;
  `sort=property,direction` repeatable, ties broken by id; anything out of range, or an unknown sort property, is
  `400 validationError`. List responses use `PagedModel` with `page: {size, number, totalElements, totalPages}`. A new
  list must reuse the `Page`, `Size` and `Sort` parameter components (`components/parameters`) and must not invent
  defaults. An unpaged list needs a stated reason.

### Methods and status codes

| Situation | Status | Notes |
| --- | --- | --- |
| Read, update, action that returns a body | 200 | |
| Create that returns the new resource | 201 + `Location` header | `Location` is the detail route of the resource. |
| Delete, or update with nothing to return | 204, no body | 404 if absent, so deletes are not idempotent on purpose. |
| Work accepted and finished later (scan start, data export) | 202 + `Location` of a status resource | |
| Malformed body, failed validation, bad page or sort | 400 | `validationError`, `data` names the parameter. |
| Missing or invalid credentials | 401 | An authenticated caller without permission is 403. Public repos can be read anonymously. |
| Authenticated but not allowed | 403 | |
| No such resource, or a private one the caller may not know exists | 404 | |
| Name already taken, state conflict | 409 | |
| Wrong method | 405 | |
| Cannot produce the requested media type | 406 | |
| Unsupported request content type | 415 | |
| Body above the multipart limit | 413 | `MaxUploadSizeExceededException`. |
| Too many failed logins | 429 + `Retry-After` | |

`POST /api/auth/logout` is a 204 (RPS-1886): it revokes the refresh token family and has nothing to return. Over quota
(403 with `diskUsageExceeded` or `trafficLimitExceeded`) is a Cloud-only plan feature; OS has no quota.

### Error body

- Failures are `application/problem+json` (RFC 9457): `type` (URI, default `about:blank`), `title`, `status`,
  `detail`, `instance`, plus these extensions: `code`, the stable machine key (today's `msgId`, for example
  `validationError`, `usernameInUse`; codes are never renamed or reused); `errors[]`, per-field failures
  `{field, code, message}`; `traceId`, the unique id of the failure for a bug report (today's `errorCode`, a UUID).
- The success body is the bare resource: `PagedModel` for lists, 201 with `Location` on create, 204 when empty; no
  `RestResponse` envelope on success. Until a format is migrated its success bodies stay as they are.
- The OCI, Maven and other protocol routes keep their own protocol error formats; this section covers the panel API
  only.

### Headers and caching

- Responses that carry a secret send `Cache-Control: no-store` (login and token refresh, deploy token create and
  rotate, `download-token`, user password reset); see `NoStore`.
- ETag and `If-Match` are not used: updates are last write wins. `Idempotency-Key` is not supported: creates answer
  409 on a duplicate name, which is the retry safety net.

## API spec

`repsy-backend/src/main/resources/openapi/openapi-spec.yaml` is the single source of truth for the
panel API. Edit that file for any API change; there is no other copy. Both sides are generated from it:

- Backend DTOs: `openapi-generator-maven-plugin` in `repsy-backend/pom.xml` writes them to
  `target/generated-sources/openapi` during the Maven build.
- Frontend client: `pnpm gen:api` in `repsy-frontend/` writes `src/generated/api`, which is git-ignored.
  Re-run it after the spec changes. The `Dockerfile` runs the same generator.

- **The spec is the single source.** Change the spec first, then the controller, then regenerate the clients; never
  the other way round. Regenerate in the same PR: `pnpm gen:api` in `repsy-frontend/` and `pnpm gen:api` in `e2e/`
  (the e2e harness client). The backend DTOs are generated by the Maven build.
- **Tags (owner decision 2026-10-05, RPS-1897).** A tag is a kebab-case domain noun without the `-controller` suffix,
  and the same area has the same tag in OS and Cloud (`repos`, `deploy-tokens`, `security-scans`, `npm-packages`, ...).
  Every operation has exactly one tag. The allowed set and the old to new table (OS, Cloud panel, Cloud admin) is
  `repsy-backend/src/main/resources/openapi/openapi-tags.json`; `OpenApiSpecConsistencyIT` checks the spec against
  `allowedTags`, and the Cloud IT reuses the file from the classpath. A new tag is added to that file first. The
  generated clients are named from the tag: the Angular classes are `<Tag>Api` (`serviceSuffix=Api` in `pnpm gen:api`,
  so they never clash with a hand-written `*Service`) and the e2e `PanelClient` has one property per tag (`c.repos`,
  `c.securityScans`). Two tags that differ only in a suffix would become one class, so decide one noun per area
  (`security-scans` covers both the cross-repo scan list and the per-artifact scans).
- **Cloud = OS + `{repoOwner}`.** The Cloud spec in `repsy-mono` follows this one. A spec change here needs the matching
  change there (its `OpenApiSpecConsistencyIT` compares the two and lists the known differences); state in the PR
  whether it does and link it.
- **What `OpenApiSpecConsistencyIT` enforces** (`repsy-backend`, Failsafe): every panel route is in the spec and every
  spec operation has a handler; every other controller mapping is in the spec or in `OUTSIDE_THE_PANEL_SPEC` (the
  protocol router, the `/error` page; a stale entry fails the IT); path variable names and declared path and query
  parameters match the handler; the success responses match the handler (a `void` or `ResponseEntity<Void>` handler
  has no content in any 2xx, any other has content in a 2xx and no 204, and a 201, 202 or 204 the handler produces via
  `ResponseEntities.created/accepted/noContent`, `ResponseEntity.*`, `HttpStatus.*` or `@ResponseStatus` is
  declared); security, 401 and 403 documentation, camelCase names, `$ref`s resolve and `ProblemDetail` matches the
  real failure. Each new rule has a test that fails on a planted drift. A status a handler takes from a callee is not
  seen, so that direction of the check is one-way.
- **Known gap**: `GET /api/usage` documents no 400 (Cloud documents one for its year and month filters, which OS does
  not have: the OS handler takes no parameter that can fail validation, so there is nothing to document here).

### API tag mapping (RPS-1897)

Old springdoc tag to new tag. Several old tags may map to one new tag (one area, one generated class).

#### OS panel spec

| Old tag | New tag |
| --- | --- |
| `auth-controller` | `auth` |
| `cargo-crate-controller` | `cargo-crates` |
| `docker-cleanup-policy-controller` | `docker-cleanup-policy` |
| `docker-image-controller` | `docker-images` |
| `docker-repo-cleanup-controller` | `docker-repo-cleanup` |
| `golang-module-controller` | `golang-modules` |
| `helm-chart-controller` | `helm-charts` |
| `key-store-controller` | `maven-key-stores` |
| `maven-artifact-controller` | `maven-artifacts` |
| `maven-group-controller` | `maven-groups` |
| `npm-package-api-controller` | `npm-packages` |
| `npm-scope-api-controller` | `npm-scopes` |
| `nuget-package-controller` | `nuget-packages` |
| `profile-controller` | `profile` |
| `protocol-deploy-token-controller` | `deploy-tokens` |
| `protocol-repo-controller` | `repos` |
| `pypi-package-controller` | `pypi-packages` |
| `repo-collection-controller` | `repos` |
| `ruby-gem-api-controller` | `ruby-gems` |
| `security-scan-controller` | `security-scans` |
| `usage-controller` | `usage` |
| `user-controller` | `users` |
| `vulnerability-scan-controller` | `security-scans` |

#### Cloud panel spec

| Old tag | New tag |
| --- | --- |
| `account-controller` | `oauth-accounts` |
| `auth-controller` | `auth` |
| `billing-controller` | `billing` |
| `cargo-crate-controller` | `cargo-crates` |
| `coupon-controller` | `coupons` |
| `custom-domain-controller` | `custom-domains` |
| `data-export-controller` | `data-exports` |
| `data-export-file-controller` | `data-exports` |
| `docker-cleanup-policy-controller` | `docker-cleanup-policy` |
| `docker-image-controller` | `docker-images` |
| `docker-repo-cleanup-controller` | `docker-repo-cleanup` |
| `email-bulletin-controller` | `email-bulletins` |
| `golang-module-controller` | `golang-modules` |
| `health-controller` | `logs` |
| `helm-chart-controller` | `helm-charts` |
| `key-store-controller` | `maven-key-stores` |
| `mailjet-webhook-controller` | `mailjet-webhooks` |
| `maven-artifact-controller` | `maven-artifacts` |
| `maven-group-controller` | `maven-groups` |
| `mfa-controller` | `mfa` |
| `npm-package-api-controller` | `npm-packages` |
| `npm-scope-api-controller` | `npm-scopes` |
| `nu-get-package-controller` | `nuget-packages` |
| `nuget-package-controller` | `nuget-packages` |
| `profile-controller` | `profile` |
| `protocol-cache-controller` | `cache` |
| `protocol-deploy-token-controller` | `deploy-tokens` |
| `protocol-proxy-controller` | `proxies` |
| `protocol-repo-controller` | `repos` |
| `protocol-security-controller` | `security-scans` |
| `protocol-user-controller` | `repo-users` |
| `protocol-webhook-controller` | `webhooks` |
| `public-profile-controller` | `public-profiles` |
| `pypi-package-controller` | `pypi-packages` |
| `repo-collection-controller` | `repos` |
| `repo-lookup-controller` | `lookup` |
| `repo-security-summary-controller` | `security-scans` |
| `ruby-gem-api-controller` | `ruby-gems` |
| `security-scan-controller` | `security-scans` |
| `stats-controller` | `usage` |
| `stripe-controller` | `stripe` |
| `subscription-plan-controller` | `subscriptions` |
| `support-controller` | `support` |
| `ticket-controller` | `tickets` |
| `usage-controller` | `usage` |
| `vulnerability-scan-controller` | `security-scans` |
| `webhook-controller` | `stripe-webhooks` |

#### Cloud admin spec

| Old tag | New tag |
| --- | --- |
| `admin-tenant-controller` | `tenants` |
| `admin-user-controller` | `admin-stats` |
| `coupon-controller` | `coupons` |
| `custom-domain-controller` | `custom-domains` |
| `data-export-controller` | `data-exports` |
| `docker-cleanup-policy-controller` | `docker-cleanup-policy` |
| `mail-controller` | `emails` |
| `maintenance-controller` | `maintenance` |
| `stats-maintenance-controller` | `maintenance` |
| `subscription-maintenance-controller` | `maintenance` |
| `tenant-controller` | `tenants` |
| `usage-maintenance-controller` | `maintenance` |

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
  same transaction that saves the manifest (`ImageTxService.getOrCreateImage`, an insert that skips an
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
`repsy-core` merges to `main` the same way, without a merge queue.

repsy-core releases are tags only (no published artifacts, RPS-1085). After a core release, bump
`<parent><version>` in `pom.xml` together with the pointer.
