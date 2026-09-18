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

## Database

- PostgreSQL 18 is the supported production database; embedded H2 is also supported (see
  `README.md`).
- Schema is managed by Flyway in `repsy-backend/src/main/resources/db/migration/` (PostgreSQL
  scripts under `postgresql/`), named `V{version}__{description}.sql`. Add a new migration rather
  than editing an existing one.
- Use `postgres:18` in any Dockerfile, compose file, README snippet or test you add.

## Submodule

`core/` is pinned to a specific `repsy-core` commit. Bump it only as a deliberate change (its own
PR, or a clearly called-out part of one), and re-run the core install above afterwards.
