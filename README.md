<a href="https://repsy.io" target="_blank"><img src="./repsy-frontend/src/assets/images/repsy.png" alt="Repsy Logo" width="200"/></a>
# Repsy: The Open Source Universal Package Repository

**Repsy** is an open-source, universal package repository that makes it easy to host, manage, and distribute your packages across multiple ecosystems — all in one place. With support for popular formats including Golang, Cargo (Rust), Docker, Maven, NPM, PyPI, and more, Repsy helps streamline your development workflows and supports teams of any size.
## Table of Contents

- [Features](#features)
- [Quick Start](#quick-start)
- [HTTPS / SSL](#https--ssl)
- [Vulnerability Scanning](#vulnerability-scanning)
- [Installation](#installation)
    - [Using Docker (H2 - Embedded)](#option-1-docker-with-h2-embedded-database)
    - [Using Docker (PostgreSQL)](#option-2-docker-with-postgresql)
    - [Using Docker Compose (PostgreSQL)](#option-3-docker-compose-with-postgresql)
    - [Manual Installation](#manual-installation)
- [Upgrading](#upgrading)
- [Configuration](#configuration)
- [Content Security Policy](#content-security-policy)
- [Cross-Origin Requests (CORS)](#cross-origin-requests-cors)
- [Usage](#usage)
- [Reverse Proxy](#reverse-proxy)
- [Troubleshooting](#troubleshooting)
- [Development](#development)
- [License](#license)

## Features

- **Repository Management**: Create, manage, and organize repositories
- **Multi-Protocol Support**: Golang, Cargo (Rust), Maven, npm, PyPI, Docker registries
- **User Authentication**: Secure JWT-based authentication
- **Deploy Tokens**: Secure token-based deployment mechanism
- **Real-time Dashboard**: Monitor repository activity
- **RESTful API**: Comprehensive REST API
- **Database Support**: H2 (embedded) and PostgreSQL
- **Docker Ready**: Complete containerization support
- **Vulnerability Scanning**: Vulnerability scanning (Maven, npm, PyPI, Docker) via an optional Trivy-based scanner service.

## Quick Start

The fastest way to get Repsy up and running (uses embedded H2 database — no external dependencies):

```bash
docker run -d \
  --name repsy \
  -p 8080:8080 \
  -p 9090:9090 \
  repo.repsy.io/repsy/os/repsy:latest
```

Access the application:
- **Backend API & Frontend (Web UI)**: http://localhost:8080
- **Repository Operations**: http://localhost:9090

**Default Admin Credentials:**
- Username: `admin`
- Password: since `ADMIN_INITIAL_PASSWORD` is not set, a random password is generated on first startup. Retrieve it from the logs:
  ```bash
  docker logs repsy | grep "password"
  ```

> **Note:** In the image, the H2 database (`/app/data/repsy`) and the artifact files (`STORAGE_BASE_PATH`, default `/app/data/storage`) both live under `/app/data`, so one volume keeps both. The command above has no volume: everything is lost when the container is removed. Add `-v repsy-data:/app/data` to keep it (see [Option 1](#option-1-docker-with-h2-embedded-database)).

## HTTPS / SSL

Repsy OS supports optional, per-port HTTPS. HTTP ports (8080, 9090) remain open at all times — SSL adds new ports alongside them and does not replace them.

### Generating a self-signed certificate

```bash
keytool -genkeypair \
  -alias repsy \
  -keyalg RSA \
  -keysize 2048 \
  -storetype PKCS12 \
  -keystore keystore.p12 \
  -validity 365 \
  -storepass changeit \
  -keypass changeit \
  -dname "CN=localhost, O=Repsy" \
  -ext "SAN=DNS:localhost,IP:127.0.0.1"
```

### Environment variables

| Variable | Description | Default |
|---|---|---|
| `API_SSL_ENABLED` | Enable HTTPS for the API port | `false` |
| `API_SSL_PORT` | HTTPS port for the API | `8443` |
| `API_SSL_KEY_STORE_PATH` | Path to keystore file (e.g. `file:/app/certs/api.p12`) | — |
| `API_SSL_KEY_STORE_PASSWORD` | Keystore password | — |
| `API_SSL_KEY_STORE_TYPE` | Keystore type | `PKCS12` |
| `API_SSL_KEY_ALIAS` | Key alias inside the keystore | `repsy` |
| `REPO_SSL_ENABLED` | Enable HTTPS for the repo port | `false` |
| `REPO_SSL_PORT` | HTTPS port for the repo | `9443` |
| `REPO_SSL_KEY_STORE_PATH` | Path to keystore file (e.g. `file:/app/certs/repo.p12`) | — |
| `REPO_SSL_KEY_STORE_PASSWORD` | Keystore password | — |
| `REPO_SSL_KEY_STORE_TYPE` | Keystore type | `PKCS12` |
| `REPO_SSL_KEY_ALIAS` | Key alias inside the keystore | `repsy` |

### Docker run example

```bash
docker run \
  -v /host/certs/keystore.p12:/app/certs/keystore.p12 \
  -e API_SSL_ENABLED=true \
  -e API_SSL_KEY_STORE_PATH=file:/app/certs/keystore.p12 \
  -e API_SSL_KEY_STORE_PASSWORD=changeit \
  -e REPO_SSL_ENABLED=true \
  -e REPO_SSL_KEY_STORE_PATH=file:/app/certs/keystore.p12 \
  -e REPO_SSL_KEY_STORE_PASSWORD=changeit \
  -p 8080:8080 -p 8443:8443 -p 9090:9090 -p 9443:9443 \
  repsy-os:latest
```

### File permission note

> Mounted keystore files must be readable by the container user.
> On the host, ensure the file has at least mode `644`:
> ```bash
> chmod 644 /host/certs/keystore.p12
> ```

### Production note

> For production deployments, use a CA-signed certificate instead of a self-signed one.
> Self-signed certificates will cause `x509: certificate signed by unknown authority` errors
> in clients unless the certificate is explicitly trusted.

## Vulnerability Scanning

Repsy can scan pushed artifacts (Maven, npm, PyPI, Docker) for known vulnerabilities using a separate `repsy-scanner-trivy` service. This is **disabled by default** (`SECURITY_SCANNER=disabled`) and adds no dependency to a plain install. To enable it, run the `repsy-scanner-trivy` service (see [Option 3](#option-3-docker-compose-with-postgresql) and [`repsy-scanner-trivy/README.md`](./repsy-scanner-trivy/README.md)) and set `SECURITY_SCANNER=enabled` along with the `TRIVY_*`/`DOCKER_INTERNAL_REGISTRY_BASE_URL` variables in [Environment Variables](#environment-variables).

The scanner is published with every release as `repo.repsy.io/repsy/os/repsy-scanner-trivy`, under the same tags as the application image (`repo.repsy.io/repsy/os/repsy`): a release tag without the leading `v` (for example `26.10.0`) and `latest`. Run the application and the scanner of the **same release**: the HTTP contract between them (`POST /scan`, `GET /scan/{scanId}`, the `X-Scanner-Api-Key` header) is not versioned, so a mixed pair is not supported. [`examples/docker-compose.scanner.yml`](./examples/docker-compose.scanner.yml) is a complete Compose example (PostgreSQL, Repsy, the scanner and the `trivy-cache` volume) that pins both images with one `REPSY_VERSION`.

Each repository has a security scan setting that controls whether newly pushed versions are scanned automatically. It does not block manual scans: a version can always be scanned on demand from the panel or with `POST /api/repos/{repoName}/artifacts/{artifactName}/versions/{version}/scan`, even when the repository's setting is off.

### What a scan covers

A scan covers **what the artifact contains**, not what it declares. For Maven, npm and PyPI the scanner unpacks the stored file and runs `trivy rootfs` on it. `rootfs` reads installed packages (a `node_modules` directory, jars, a Python `.dist-info`); it does not read lock files and does not resolve declared dependencies. So:

| Format | What is scanned | What is not |
| --- | --- | --- |
| npm | the tarball, so packages bundled in it (`node_modules/*/package.json`) | the package's `dependencies`, `devDependencies` and `peerDependencies`, and any `package-lock.json` in the tarball |
| Maven | the main file of the version (a jar, or a war, ear or rar for that packaging), including the jars nested in it | the dependencies declared in the POM: a thin jar is scanned as itself only |
| PyPI | the sdist (`.tar.gz`) if the release has one, otherwise the first matching file, such as a wheel; the package's own metadata | the `Requires-Dist` dependencies |
| Docker | the whole image, which the scanner pulls from Repsy by reference | |

A package that declares vulnerable dependencies without bundling them is therefore reported as having no findings, and a clean scan does not mean the dependency tree is clean. Scans use the Trivy vulnerability database the scanner holds locally, and only the four formats above are scanned.

### Auditing npm packages

`npm audit`, `pnpm audit`, `yarn npm audit` and `bun audit` work against a Repsy npm repository. They report the vulnerabilities the scanner found for the package versions they ask about, taken from the scans of **that repository** only (the latest completed scan of each version). With the repository's security scan setting off (the scanner is disabled, or scanning is turned off for that repository, even if an earlier scan left findings), or before a version has been scanned, they report none and exit with 0. Repsy reports as vulnerable only the versions the audit asks about and a scan found the vulnerability in, so it never flags a version it has not seen. Because a scan covers only what a tarball bundles (see [What a scan covers](#what-a-scan-covers)), an advisory is reported only for a package name and version that some scanned tarball of that repository bundled. Most npm packages bundle nothing, so `npm audit` against Repsy reports far less than `npm audit` against npmjs.org would for the same dependency tree: an empty audit means no scanned package of this repository contains a known vulnerability, not that the consumer's dependencies have none. An audit request is limited to 8 MiB (inflated) and 20,000 packages. `yarn audit` (Yarn 1) always queries `registry.yarnpkg.com` and never reaches Repsy. `npm whoami`, `npm ping` and `npm search` are answered as well: `whoami` needs credentials even on a public repository, and `search` looks only at the packages of the repository in the URL. `search` understands the `scope:`, `keywords:`, `author:`, `maintainer:`, `is:`/`not:` (`deprecated`, `unstable`, `insecure`) and `boost-exact:` qualifiers, `size` (0 to 250) and `from`; a text of qualifiers Repsy cannot filter on matches no package, and a `size` or `from` that is not a whole number of 0 or more is answered with 400. `npm logout` and `pnpm logout` revoke the token of an `npm login` (`DELETE /-/user/token/<token>`, `200 {"ok": true}`) so that it is refused from then on; the secret of a deploy token is refused (403 `deployTokenNotRevocable`, with an `error` text that says so), because a deploy token is managed in the web UI and revoking it would take it away from every CI job that shares it. So a CI job that authenticates with a deploy token should not run `npm logout` (or should ignore its exit code): `npm` exits non-zero (`E403`, and prints the text) and keeps the token in `.npmrc`, `pnpm` exits non-zero (`ERR_PNPM_LOGOUT_FAILED`), and revoking the deploy token is done in the web UI. With Basic `_auth` (`username:password` in `.npmrc`) there is no token to revoke: `npm logout` stops with `ENEEDAUTH` and `pnpm logout` with `ERR_PNPM_NOT_LOGGED_IN`, both before they call the registry.

## Installation

### Option 1: Docker with H2 (Embedded Database)

No external database required. Suitable for evaluation and development.

```bash
docker run -d \
  --name repsy \
  -p 8080:8080 \
  -p 9090:9090 \
  -e ADMIN_INITIAL_PASSWORD=YourSecurePassword123 \
  -v repsy-data:/app/data \
  repo.repsy.io/repsy/os/repsy:latest
```

> The `-v repsy-data:/app/data` flag persists the H2 database and the artifact files (`/app/data/storage`) across container recreation. Without a volume both are lost when the container is removed.

### Option 2: Docker with PostgreSQL

```bash
# 1. Create a shared network
docker network create repsy-network

# 2. Start PostgreSQL
docker run -d \
  --name repsy-postgres \
  --network repsy-network \
  -e POSTGRES_DB=repsy \
  -e POSTGRES_USER=repsy \
  -e POSTGRES_PASSWORD=repsy123 \
  -v repsy-pgdata:/var/lib/postgresql \
  -p 5432:5432 \
  postgres:18

# 3. Start Repsy
docker run -d \
  --name repsy \
  --network repsy-network \
  -p 8080:8080 \
  -p 9090:9090 \
  -e DB_URL=jdbc:postgresql://repsy-postgres:5432/repsy \
  -e DB_USERNAME=repsy \
  -e DB_PASSWORD=repsy123 \
  -e ADMIN_INITIAL_PASSWORD=YourSecurePassword123 \
  -v repsy-data:/app/data \
  repo.repsy.io/repsy/os/repsy:latest
```

> Two volumes keep this install across container recreation: `repsy-data` for the artifact files (`/app/data/storage`) and `repsy-pgdata` for the database. Mount PostgreSQL 18's volume at `/var/lib/postgresql`: it keeps its data in a versioned directory below that path, so a volume at `/var/lib/postgresql/data` does not persist it.

### Option 3: Docker Compose with PostgreSQL

```yaml
services:
  postgres:
    container_name: repsy-postgres
    hostname: repsy-postgres
    image: postgres:18
    environment:
      - POSTGRES_DB=repsy
      - POSTGRES_USER=repsy
      - POSTGRES_PASSWORD=repsy123
    volumes:
      - repsy-pgdata:/var/lib/postgresql
    ports:
      - "5432:5432"
    networks:
      - repsy-network

  repsy:
    container_name: repsy
    image: repo.repsy.io/repsy/os/repsy:latest
    depends_on:
      - postgres
    ports:
      - "8080:8080"
      - "9090:9090"
    environment:
      - DB_URL=jdbc:postgresql://repsy-postgres:5432/repsy
      - DB_USERNAME=repsy
      - DB_PASSWORD=repsy123
      - ADMIN_INITIAL_PASSWORD=YourSecurePassword123
    volumes:
      - repsy-data:/app/data
    networks:
      - repsy-network

networks:
  repsy-network:
    driver: bridge

volumes:
  repsy-data:
  repsy-pgdata:
```

#### Adding vulnerability scanning to the stack

To enable vulnerability scanning, add the following service to your docker-compose.yml (a complete file is [`examples/docker-compose.scanner.yml`](./examples/docker-compose.scanner.yml)). Use the same tag as your `repsy` service instead of `latest` when you pin a release:

```yaml
  repsy-scanner-trivy:
    container_name: repsy-scanner-trivy
    hostname: repsy-scanner-trivy
    image: repo.repsy.io/repsy/os/repsy-scanner-trivy:latest
    environment:
      - SCANNER_API_KEY=${TRIVY_SCANNER_API_KEY:-changeme-trivy-api-key}
    ports:
      - "8090:8090"
    extra_hosts:
      - "host.docker.internal:host-gateway"
    volumes:
      - trivy-cache:/home/appuser/.cache/trivy
    healthcheck:
      test: ["CMD", "wget", "-q", "-O-", "http://localhost:8090/health"]
      interval: 30s
      timeout: 5s
      start_period: 10s
      retries: 3
    networks:
      - repsy-network

volumes:
  trivy-cache:
```

This supports two topologies:

- **(a) Full compose** — every service (`postgres`, `repsy`, `repsy-scanner-trivy`) runs
  in the compose stack. The `repsy` service's environment gets `SECURITY_SCANNER=enabled`,
  `TRIVY_SCANNER_BASE_URL=http://repsy-scanner-trivy:8090`, and
  `DOCKER_INTERNAL_REGISTRY_BASE_URL=http://repsy:9090` (service-name-based DNS resolution
  on `repsy-network`).
- **(b) Hybrid (backend on host)** — the common day-to-day dev setup: `repsy-backend`
  runs from the IDE / `mvn spring-boot:run` directly on the host, and only `postgres` +
  `repsy-scanner-trivy` run in the compose stack. The host-run backend needs its own env
  vars set directly, pointing at the stack's published ports:
  `DB_URL=jdbc:postgresql://localhost:5432/repsy`, `SECURITY_SCANNER=enabled`,
  `TRIVY_SCANNER_BASE_URL=http://localhost:8090`, `TRIVY_SCANNER_API_KEY=<same value as
  the compose stack's TRIVY_SCANNER_API_KEY>`, and
  `DOCKER_INTERNAL_REGISTRY_BASE_URL=http://host.docker.internal:9090` (this last one only
  resolves from inside `repsy-scanner-trivy` because of the `extra_hosts` entry above —
  without it, Linux Docker does not auto-map `host.docker.internal` the way Docker Desktop
  does on macOS/Windows).

See [`repsy-scanner-trivy/README.md`](./repsy-scanner-trivy/README.md) for scanner service
details (standalone build/run instructions, its own environment variables, and API usage).

### Manual Installation

**Prerequisites:**
- **Java**: JDK 25
- **Spring Boot**: 4.0.5
- **PostgreSQL**: 18 (unless you pass `DB_URL` for the embedded H2 database, see step 3)
- **Angular**: 21
- **Maven**: 3.9.7 or higher
- **Node.js** 24.x (>=24.0.0 <25.0.0)

```bash
# 1. Build the backend
mvn clean install -DskipTests

# 2. Install frontend dependencies
cd repsy-frontend
pnpm install
cd ..

# 3. Run against PostgreSQL (see prerequisites above), or set DB_URL for a zero-dependency
#    embedded H2 database instead, e.g.:
#    DB_URL='jdbc:h2:file:/tmp/repsy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE' \
#      mvn spring-boot:run
cd repsy-backend
mvn spring-boot:run
```

Access at:
- **Frontend (Web UI)**: http://localhost:4200
- **Backend API**: http://localhost:8080
- **Repository Operations**: http://localhost:9090

## Upgrading

### Artifact storage moved to `/app/data/storage` in the Docker image (RPS-1401)

Earlier images did not set `STORAGE_BASE_PATH`, so artifacts went to `/home/appuser/.repsy`, inside the container's writable layer and not on the `/app/data` volume: a recreated container kept its database and lost every artifact. The image now defaults `STORAGE_BASE_PATH` to `/app/data/storage`, on the volume.

**Who is affected:** an existing container whose artifacts are in `/home/appuser/.repsy` (a bind mount of it, or a container that is only restarted, never recreated).

**What happens:** on startup `entrypoint.sh` keeps using `/home/appuser/.repsy` when `STORAGE_BASE_PATH` is still the image default, `/app/data/storage` is empty and `/home/appuser/.repsy` holds data. It logs a `WARN: ... holds artifacts and /app/data/storage is empty` line, so nothing disappears on upgrade. Without a mount of that directory, recreating the container has always lost the artifacts; nothing can restore them.

**To move to the new default** (once): stop Repsy, copy the content of `/home/appuser/.repsy` into `/app/data/storage` (for example `docker cp repsy:/home/appuser/.repsy/. ./repsy-storage` and then copy that directory into the volume with a helper container, or bind-mount your old directory at `/app/data/storage`), and start again. Or set `STORAGE_BASE_PATH=/home/appuser/.repsy` explicitly and keep mounting it.

### `DB_HOST`, `DB_PORT` and `DB_DATABASE` are no longer read (RPS-1173 / RPS-1423)

Releases up to `v26.08.4` built the PostgreSQL URL from `DB_HOST`, `DB_PORT` and `DB_DATABASE`. Only `DB_URL` is read now, and the Docker image defaults it to an embedded H2 file. A container that is upgraded with the old variables and no `DB_URL` therefore starts on a new, empty H2 database: the panel shows a fresh installation and the PostgreSQL data looks lost (it is untouched).

Set `DB_URL=jdbc:postgresql://<host>:<port>/<database>` instead (with `DB_USERNAME` and `DB_PASSWORD`). To help, Repsy logs a `WARN` at startup whenever any of the three variables is set, whatever `DB_URL` is:

```
The environment variables DB_HOST, DB_PORT are no longer read: only DB_URL selects the database. To keep using PostgreSQL set DB_URL=jdbc:postgresql://pg:5432/repsy (with DB_USERNAME and DB_PASSWORD).
```

When the effective database is H2 it adds that Repsy is starting on the embedded H2 database, not PostgreSQL. It never prints `DB_URL` itself, since that may carry credentials. Repsy still starts.

### Password reset when upgrading past the BCrypt migration (RPS-961 / RPS-1033)

The first release that contains both RPS-961 (hashing passwords with BCrypt) and RPS-1033
(retiring the legacy salted SHA-256 verification path) resets the password of every account that
has not logged in since RPS-961 shipped. The latest release, `v26.08.4`, contains neither change,
so this applies starting with the next release.

**What happens:** a SHA-256 hash cannot be converted to BCrypt without the plain-text password,
so migration `V0017__Drop_User_Salt.sql` sets the empty-hash password-reset marker on every
account whose hash is not already BCrypt, revokes that account's refresh tokens, and drops the
now-unused `users.salt` column. An account that has already logged in since RPS-961 shipped
already has a BCrypt hash and is unaffected. This is a one-time migration: it does not run again
on later upgrades.

**What you'll see:** on startup, `AdminUserInitializer` generates a new password for every admin
account left with the reset marker and logs it at `WARN`:

```
Admin password has been reset for user <username>. New password: <password>
```

Copy that password from the log right after the upgrade; it is not stored anywhere and is not
logged again.

**Resetting other users:** a non-admin account left with the reset marker cannot log in until an
admin resets its password, either from the users page in the web UI or directly with
`POST /api/users/{userId}/actions/reset-password`. If no admin can sign in either, a
[password reset marker file](#forgot-admin-password) resets the password of any account, not only
an admin's, from inside the container.

### `npm unpublish` and Helm chart delete need the `ADMIN` role (RPS-1424)

Before this change, a `USER` account and a read-write deploy token could remove
stored files through two package clients although the web UI restricted that to `ADMIN`: `npm
unpublish` (one version or a whole package) and deleting a Helm chart version with `DELETE
/api/charts/<name>/<version>`. Both are manage operations now, like the web UI's delete and like
Docker's manifest delete: they need an `ADMIN` account, and a deploy token, read-write or read-only,
is refused for any manage operation in every format. See [Repository Access](#repository-access).

**Who is affected:** a CI job that runs `npm unpublish` or deletes chart versions with a deploy token
or with a non-admin account. It now gets `401` and the command fails. Use an `ADMIN` account for
those jobs, or delete the version in the web UI.

**What does not change:** `npm publish`, `npm deprecate`, `npm dist-tag add` and `rm`, `cargo yank`,
NuGet unlist and relist, `gem yank` and publishing a Helm chart still need only write access, so a
read-write deploy token keeps running them.

### Docker manifests are content-addressed (RPS-1216)

Docker manifests used to be stored as a child of a tag: pushing a tag again with a new manifest
rewrote the tag's manifest in place, so the previous manifest could no longer be pulled by its
digest (`image@sha256:...`). A manifest is now stored once per image and digest, and a tag is only a
pointer to it. Upgrading needs a database migration (`V0024`) and renames the stored manifest files.

**Back up the database and the storage directory before upgrading.** Flyway migrations are
forward-only, and once the manifest files have been renamed the previous version can no longer read
them: to go back, restore the backup. (The previous version cannot push against the migrated schema
either.)

**What the migration does** (in one transaction, on PostgreSQL and on H2):

- one `docker_manifest` row per image and digest: the copies that older versions kept per tag (and
  the rows they added for the children of a multi-platform tag) are folded into the row of the
  original push;
- each tag points to the row of its digest; the extra `sha256:...` and `sha512:...` tags that a
  digest-pushed index or a `sha512` push left behind are removed (their manifests stay, pullable by
  digest), and so is a tag that already answered `404` because its manifest row was gone;
- a multi-platform tag's children are recorded as the index's references;
- rows of failed pushes that belonged to no tag are removed.

**The file rename** is done by a background job that starts 10 minutes after the application
(`DOCKER_MANIFEST_LAYOUT_REPAIR_INITIAL_DELAY`), reads every legacy manifest file, checks that its
bytes hash to the recorded digest, renames it to `manifests/<digest>` and records the `sha512`
digest. It is idempotent and resumable: a rerun (or a restart in the middle) finishes what is left.
Until a manifest has been renamed it is served from its old file name, so pulls keep working during
and after the upgrade. A manifest whose file is missing or does not match its digest is logged at
`WARN` and left exactly as it was. Progress and results are logged at `INFO`
(`Docker manifest layout repair: ...`).

### NuGet versions with build metadata (RPS-996 / RPS-1059 / RPS-1123)

NuGet ignores build metadata when it compares versions: `1.0.0+a` and `1.0.0` are the same version. The first release that contains RPS-996 (a version is stored, and found, by its version without build metadata), RPS-1059 (the migration below) and RPS-1123 (no lookup by the old spelling any more) stores a pushed `1.0.0+a` as `1.0.0`. The latest release, `v26.08.4`, contains none of these changes, so this applies starting with the next release.

**What happens:** versions that an earlier release stored with build metadata (`1.0.0+a`, in a directory of its own) are moved to their version without it (`1.0.0`) by a runner that starts with the application: it copies the files, renames the database row and the scans, and removes the old directory. It is idempotent, and a version that fails is retried on the next start. A database without such versions costs one query and logs nothing. Progress is logged at `INFO` (`NuGet versions stored with build metadata: ...`).

**Conflicts:** when `1.0.0` already exists next to `1.0.0+a` the runner cannot decide which upload is the right one. It leaves both, logs each conflict (repository, package, version) at `WARN` on every start, and never drops one. Until you act, a request for `1.0.0+a` is answered with the files of `1.0.0`, and both still show in the version list.

**To resolve a conflict:** compare both uploads and then

- to keep `1.0.0` and drop the other one, delete the `1.0.0+a` entry in the web UI. This removes only that entry and its directory;
- to keep the other upload, delete `1.0.0` in the web UI and restart Repsy: the runner then promotes `1.0.0+a` to `1.0.0`.

Deleting `1.0.0+a` when there is no such entry (the usual case) still deletes `1.0.0`, since they are one version. Releases before this change deleted `1.0.0` in both cases, so a conflict could not be cleaned up by deleting its entry.

**Removal of the runner:** it is kept in this release and in every release within about six months of it. It is removed in the first release after that (about `27.03.x`), together with this section and with the sentence "Installations older than N must upgrade to N..27.02 first." (N is the release that first contains the runner, and 27.02 the last release that still has it). An installation that skips from an older release to one without the runner keeps its versions stored with build metadata: they are not served, and the panel shows them under their old spelling.

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `ADMIN_INITIAL_PASSWORD` | Initial admin password. Only applied on first startup when no admin exists. | *(empty)* |
| `DB_URL` | JDBC database URL. The Docker image defaults to the embedded H2 database; running from source (`mvn spring-boot:run`) defaults to PostgreSQL on `localhost:5432` instead. `DB_HOST`, `DB_PORT` and `DB_DATABASE` are not read (Repsy warns at startup if they are set). | `jdbc:h2:file:/app/data/repsy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` (image only) |
| `DB_USERNAME` | Database username | `repsy` |
| `DB_PASSWORD` | Database password | `repsy123` |
| `STORAGE_BASE_PATH` | Base directory for artifact file storage. Set to a path inside `/app/data` (e.g. `/app/data/storage`) to persist artifacts with a single volume mount (the Docker image already does). Deleting a repo, a package or a version moves its files into a `trash/` directory of the protocol (for example `maven/trash`) first; the trash older than `TRASH_RETENTION` is removed every day (see `TRASH_CLEANUP_ENABLED`). | `~/.repsy` (`/app/data/storage` in the Docker image) |
| `OS_APP_JWT_SECRET` | JWT signing secret. If not set, a random 256-bit secret is generated on every startup — every restart/redeploy invalidates all existing sessions, forcing every user to log in again. Set a stable, secure random value for any production/self-host deployment. | *(random, regenerated on every startup)* |
| `SERVER_PORT` | Repository operations port | `9090` |
| `API_PORT` | Backend API and Frontend web UI port | `8080` |
| `SERVER_COMPRESSION_ENABLED` | Gzip-compress JSON answers of 1 KB or more on the repository port (an npm packument of a package with many versions is megabytes of JSON). Set to `false` when a reverse proxy in front already compresses | `true` |
| `H2_TCP_SERVER_ENABLED` | Enable the H2 TCP server. It binds loopback-only, so it is reachable only from inside the same container/host, not from an external client | `false` |
| `H2_TCP_SERVER_PORT` | H2 TCP server port | `9092` |
| `SECURITY_SCANNER` | Enables vulnerability scanning of pushed artifacts (`enabled`/`disabled`) | `disabled` |
| `TRIVY_SCANNER_BASE_URL` | Base URL of the `repsy-scanner-trivy` service | `http://localhost:8090` |
| `TRIVY_SCANNER_API_KEY` | Shared API key sent to the scanner service (must match its `SCANNER_API_KEY`). The scanner's own settings (`SCANNER_API_KEY`, `TRIVY_TIMEOUT_SECONDS`, `TRIVY_DB_REPOSITORY`, ...) are listed in [`repsy-scanner-trivy/README.md`](./repsy-scanner-trivy/README.md) | *(empty)* |
| `DOCKER_INTERNAL_REGISTRY_BASE_URL` | Base URL the scanner uses to pull Docker images from this instance's own registry | `http://localhost:9090` |
| `TRIVY_REQUEST_TIMEOUT_SECONDS` | Timeout of one HTTP request from Repsy to the scanner (submitting a scan, reading its status) | `10` |
| `TRIVY_POLL_INTERVAL_MS` | How often Repsy asks the scanner for the status of an unfinished scan | `3000` |
| `TRIVY_MAX_SCAN_DURATION_SECONDS` | How long Repsy waits for a scan to finish before it marks the scan failed | `330` |
| `TRIVY_SUBMIT_MAX_ATTEMPTS` | How many times Repsy submits a scan when it cannot reach the scanner (connection refused, DNS failure, connection reset, as while the scanner restarts), the first submit included. `1` turns the retry off. A scanner that answers with an error or does not answer in time is not retried; re-run that scan from the panel (1 to 5) | `3` |
| `TRIVY_SUBMIT_RETRY_INITIAL_DELAY_SECONDS` | Wait before the first retry of an unreachable scanner (1 to 300) | `15` |
| `TRIVY_SUBMIT_RETRY_MAX_DELAY_SECONDS` | Longest wait before any retry; the wait grows fourfold per retry (15 s, then 60 s). Keep the waits well below `TRIVY_MAX_SCAN_DURATION_SECONDS`, and note a pending retry is lost when Repsy restarts (the scan is then marked failed after that duration) (initial delay to 300) | `60` |
| `BASIC_AUTH_CACHE_ENABLED` | Remember successful HTTP Basic password checks, so a client that sends its username and password on every request pays for one password verification instead of one per request. See [Authenticating from CI](#authenticating-from-ci). | `true` |
| `BASIC_AUTH_CACHE_TTL_SECONDS` | How long a remembered password check stays valid | `300` |
| `BASIC_AUTH_CACHE_MAX_ENTRIES` | How many remembered password checks are kept | `10000` |
| `AUTH_THROTTLE_ENABLED` | Limit the failed password checks of one client (HTTP Basic, unrecognised Bearer tokens and web UI login), so a flood of wrong credentials cannot keep the CPU busy with password verification. A client over the limit is answered with `429 Too Many Requests`. See [Authenticating from CI](#authenticating-from-ci). | `true` |
| `AUTH_THROTTLE_MAX_FAILURES` | How many failed password checks one client may make per window before its next password check is refused | `20` |
| `AUTH_THROTTLE_WINDOW_SECONDS` | Length of the window in seconds. When it ends, the client starts again with a clean count | `60` |
| `AUTH_THROTTLE_MAX_CLIENTS` | How many clients are tracked at once | `10000` |
| `PASSWORD_RESET_MARKER_ENABLED` | Reset a user's password when a file named after the user is created in `PASSWORD_RESET_MARKER_DIR`. Nothing is reachable over the network: it takes write access to that directory. See [Forgot admin password?](#forgot-admin-password). Set it to `false` to switch the feature off | `true` |
| `PASSWORD_RESET_MARKER_DIR` | Directory watched for password reset marker files. The Docker image sets it to `/app/data/password-reset`, on the persisted volume, whatever `STORAGE_BASE_PATH` is | `<STORAGE_BASE_PATH>/password-reset` (`/app/data/password-reset` in the image) |
| `PASSWORD_RESET_MARKER_POLL_INTERVAL` | How often a running instance looks into the directory (ISO-8601 duration, at least `PT1S`). A marker created while the application is stopped is applied once at startup | `PT5S` |
| `ABANDONED_UPLOAD_CLEANUP_ENABLED` | Periodically delete Docker and Helm OCI blob uploads that were started and never finished (aborted pushes), and release the disk usage they were charged for | `true` |
| `ABANDONED_UPLOAD_TTL` | How long an upload can go without receiving data before it counts as abandoned (ISO-8601 duration) | `PT24H` |
| `ABANDONED_UPLOAD_CLEANUP_INTERVAL` | How often the cleanup runs (ISO-8601 duration) | `PT1H` |
| `ABANDONED_UPLOAD_CLEANUP_INITIAL_DELAY` | How long after startup the first cleanup runs (ISO-8601 duration) | `PT10M` |
| `PENDING_SIGNATURE_TTL` | How long a Maven signature that arrived before the file it signs is held (ISO-8601 duration, see [Signed Maven Deploys](#signed-maven-deploys)); older ones are deleted | `PT24H` |
| `PENDING_SIGNATURE_PURGE_INTERVAL` | How often the held signatures older than `PENDING_SIGNATURE_TTL` are deleted (ISO-8601 duration) | `PT15M` |
| `PENDING_SIGNATURE_PURGE_ENABLED` | Delete the expired held Maven signatures; set to `false` to keep them | `true` |
| `TRASH_CLEANUP_ENABLED` | Periodically empty the storage trash: what you delete (a repo, a package or a version) is moved into a `trash/` directory of its protocol first, and this job removes it from the disk for good once it is older than `TRASH_RETENTION`. **The first run after an upgrade deletes all the trash older than `TRASH_RETENTION` that has piled up so far, and that cannot be undone.** Set it to `false` to keep the trash | `true` |
| `TRASH_RETENTION` | How long deleted items stay in the trash before they are removed for good (ISO-8601 duration, at least `P1D`; a shorter value stops the application from starting). Raise it to keep deleted items recoverable for longer | `P7D` |
| `TRASH_CLEANUP_INTERVAL` | How often the trash is emptied (ISO-8601 duration) | `PT24H` |
| `TRASH_CLEANUP_INITIAL_DELAY` | How long after startup the first trash cleanup runs (ISO-8601 duration) | `PT15M` |
| `DOCKER_MANIFEST_LAYOUT_REPAIR_ENABLED` | After upgrading past RPS-1216, rename the Docker manifest files of earlier versions (named after the tag they were pushed under) to `manifests/<digest>` and record their `sha512` digest. Until a manifest is repaired it is served from its old file name, so switching the job off only delays the cleanup. See [Upgrading](#docker-manifests-are-content-addressed-rps-1216) | `true` |
| `DOCKER_MANIFEST_LAYOUT_REPAIR_INITIAL_DELAY` | How long after startup the first repair pass runs (ISO-8601 duration) | `PT10M` |
| `DOCKER_MANIFEST_LAYOUT_REPAIR_INTERVAL` | How often the repair pass runs again (ISO-8601 duration); once nothing is left to repair it costs one query | `PT24H` |
| `MULTIPART_MAX_FILE_SIZE` | Largest single file a multipart upload may carry: the package archive of a PyPI (`twine upload`), Helm (`POST /{repo}/api/charts`) or NuGet push. A larger upload is answered with `413`. Accepts a size such as `100MB` or `1GB`. A Helm chart is copied to a temporary file (in `java.io.tmpdir`) while it is checked and stored, not held in memory, so keep that directory on a disk with room for the largest chart | `500MB` |
| `MULTIPART_MAX_REQUEST_SIZE` | Largest total size of a multipart request, all parts included. Keep it at least as large as `MULTIPART_MAX_FILE_SIZE` | `500MB` |
| `RUBY_MAX_GEM_SIZE` | Largest gem a `gem push` may carry (the raw request body, so the multipart limits do not apply to it). A larger gem is answered with `413`. The gem is copied to a temporary file (in `java.io.tmpdir`) while it is checked and stored, not held in memory. Accepts a size such as `100MB` or `1GB` | `500MB` |
| `CARGO_MAX_CRATE_SIZE` | Largest `.crate` a `cargo publish` may carry (a length-prefixed field inside Cargo's own wire format, so neither the multipart limits nor `MULTIPART_MAX_FILE_SIZE` apply to it). A larger crate is answered with `413`. The crate is copied to a temporary file (in `java.io.tmpdir`) while it is checked and stored, not held in memory. crates.io itself defaults to `10MB`; raise this if you publish larger internal crates. Accepts a size such as `100MB` or `1GB` | `100MB` |
| `APP_ALLOWED_ORIGINS` | Comma-separated list of exact origins (e.g. `https://panel.example.com,https://panel-staging.example.com`) the panel API accepts cross-origin, credentialed requests from. Unset keeps today's behaviour: any origin is allowed. Set it once the panel is reachable from a known, fixed set of origins | *(empty, any origin allowed)* |
| `APP_CSP_ENABLED` | Send a `Content-Security-Policy` header with the panel SPA and its static assets (JSON API responses are unaffected). See [Content Security Policy](#content-security-policy) | `true` |
| `APP_CSP_REPORT_ONLY` | Send `Content-Security-Policy-Report-Only` instead of the enforcing header: violations are reported (in a browser that supports the Reporting API and is told where to send reports), nothing is blocked. Useful while rolling out a widened or replaced policy | `false` |
| `APP_CSP_POLICY` | Overrides the built-in Content-Security-Policy outright, so an operator can widen it (for example to allow a CDN or font host) without a rebuild. See [Content Security Policy](#content-security-policy) for the built-in policy | *(empty, built-in policy)* |

**Important Notes:**

- **Admin Username**: `admin`
- **Admin Initial Password**: Only applied when no admin user exists in the database. After first run, change your password through the application interface.
- **OS_APP_JWT_SECRET**: If left unset, a new random secret is generated in memory on every container start — since it isn't persisted, this means every restart or redeploy silently invalidates every issued access/refresh token, logging every user out at once. For any production or self-host deployment, set this to a fixed, securely generated value (e.g. `openssl rand -base64 32`) and keep it unchanged across restarts.

### Content Security Policy

Repsy sends a `Content-Security-Policy` header with the panel SPA (`GET /`, every deep SPA route)
and its static assets (JS/CSS bundles, `index.html`). It is not sent with JSON API responses
(`/api/**`), which have nothing to enforce a policy against. This is a second barrier against a
sanitiser bypass in the README viewer or any future use of raw HTML injection in the panel: even if
one slips through, the browser itself refuses to load or run content the policy does not allow.

The built-in policy:

```
default-src 'self';
script-src 'self';
style-src 'self' 'unsafe-inline';
font-src 'self' data:;
img-src 'self' data:;
connect-src 'self' <app.allowed-origins>;
object-src 'none';
base-uri 'self';
frame-ancestors 'none';
form-action 'self';
```

The panel names no third-party host: it loads no analytics, tag manager, CDN stylesheet, web font
or avatar image from another origin, so it works with no outbound internet access.
`connect-src` additionally allows whatever origins `APP_ALLOWED_ORIGINS` allows (see
[Cross-Origin Requests (CORS)](#cross-origin-requests-cors)), since a browser calling the API
cross-origin from one of those origins is exactly what CORS was configured to allow.

- Set `APP_CSP_REPORT_ONLY=true` to send `Content-Security-Policy-Report-Only` instead while
  rolling a change out: violations are reported, nothing is blocked.
- Set `APP_CSP_POLICY` to replace the built-in policy outright, for example to allow a CDN or font
  host, without a rebuild.
- Set `APP_CSP_ENABLED=false` to turn the header off entirely (for example if a reverse proxy in
  front of Repsy already sends its own).

### Cross-Origin Requests (CORS)

The panel API allows any origin to make credentialed cross-origin requests by default, which
matches the documented setups where the frontend and the API are served from different origins
(UI on `:4200`, API on `:8080`; the Docker image injects `API_BASE_URL` at runtime). Set
`APP_ALLOWED_ORIGINS` to a comma-separated list of exact origins (for example
`https://panel.example.com`) to restrict this once the panel is reachable from a known, fixed set
of origins. A preflight from any other origin is then rejected.

### Reverse Proxy

Repsy can run behind a reverse proxy (nginx, Traefik, Caddy, etc.) on a different public URL. The backend enables Spring Boot's `forward-headers-strategy: native`, which reads the `X-Forwarded-Proto`, `X-Forwarded-Host`, and `X-Forwarded-Port` headers to resolve the correct scheme/host/port instead of the internal `localhost:8080`/`:9090`.

For this to work, your proxy **must** forward these headers. Example nginx config:

```nginx
proxy_set_header Host $host;
proxy_set_header X-Forwarded-Proto $scheme;
proxy_set_header X-Forwarded-Host $host;
proxy_set_header X-Forwarded-Port $server_port;
proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
```

**Client address and the failed-login limit.** The [failed-login limit](#authenticating-from-ci) counts per client address, and behind a proxy that address is the one your proxy puts in `X-Forwarded-For`. Tomcat honours that header only when the connection comes from a trusted proxy: by default every private and loopback address (`server.tomcat.remoteip.internal-proxies`, for example `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES` as an environment variable). A client that connects directly from a public address cannot choose its own address with the header. Two things have to hold behind your proxy:

- The proxy must **append** the connecting client to `X-Forwarded-For` (`$proxy_add_x_forwarded_for` in nginx), not forward the header as it received it, or a client could pick its own address and never be limited.
- The proxy must be a trusted proxy. Otherwise every request appears to come from the proxy's own address, all clients share one count, and one client sending wrong passwords could get everybody refused for the rest of the window.

If that happens, Repsy fails safe: the limit is generous (20 failures in 60 seconds), remembered passwords and deploy tokens keep working, and each blocked address is logged once per window at `WARN` level (`Client <address> made N failed password checks ...`), so an address that turns out to be your proxy tells you what to fix. `AUTH_THROTTLE_ENABLED=false` turns the limit off if you already rate-limit failed logins in the proxy.

> **Note:** The web UI's "how to connect" config snippets for repository operations (Maven, npm, pip, etc.) use the `REPO_BASE_URL` environment variable, resolved at container startup — set it to your public repository-operations URL (e.g. `https://repo.example.com`) when running behind a reverse proxy.

**npm tarball URLs.** The npm registry names itself in the `dist.tarball` of every version it serves, whatever host, port or scheme the publisher used (the npm CLI and Yarn 1 write `http://` even for an HTTPS registry). It takes the address from `REPO_BASE_URL` (the `repsy.npm.public-url` property) when that is set, and otherwise from each request (`Host` and the `X-Forwarded-*` headers above). Set `REPO_BASE_URL` when your proxy strips a path prefix or cannot send those headers, and when the proxy is not a trusted one, because the address cannot be derived from the request there. Nothing has to be migrated: versions published earlier are served with the new address too.

**NuGet resource URLs.** The NuGet service index (`/v3/index.json`), the registration (including the `packageContent` URL of each `.nupkg`) and the search results name their URLs with an address, and `dotnet restore` follows them. Like npm, NuGet takes that address from `REPO_BASE_URL` (the `repsy.nuget.public-url` property) when that is set, followed by the repository name (`https://repo.example.com/my-repo/v3/index.json`), and otherwise from each request (`Host` and the `X-Forwarded-*` headers above). Set `REPO_BASE_URL` when your proxy strips a path prefix or cannot send those headers, so that clients are not sent to your internal address.

## Usage

### First Login

1. Navigate to http://localhost:8080
2. Login with:
    - **Username**: `admin`
    - **Password**: the value you set for `ADMIN_INITIAL_PASSWORD`

### Username Casing

Usernames are case-sensitive. Users must use the exact casing chosen when the account was
created when signing in or changing a username; for example, `MixedCase123`, `mixedcase123`, and
`MIXEDCASE123` are distinct usernames. Username uniqueness is also checked with exact casing, so
different casing variants may be registered separately.

The admin user search is case-insensitive for convenience, so a search result may not be usable
for login unless its displayed casing is entered exactly.

### Repository Access

Repsy has no per-repository owners or access lists. What a caller may do depends only on whether
they are signed in, and on their role:

| Caller | Public repository | Private repository |
| --- | --- | --- |
| Anonymous | Read | No access |
| Signed-in `USER` | Read and write | Read and write |
| `ADMIN` | Everything a `USER` can do, plus manage | Everything a `USER` can do, plus manage |

"Private" therefore means **login required**, not "restricted to specific users". Every user account
on the instance can read and modify every repository, including private ones, and can see their
names. Only *manage* operations need the `ADMIN` role: creating a repository, renaming it or
changing its description and settings, deleting it, deleting its artifacts and versions, managing
its deploy tokens, and managing users.

The web UI and the package clients follow the same rule (RPS-1424). What removes stored files is a
manage operation on the wire too: `npm unpublish` (one version or the whole package) and deleting a
Helm chart version with `DELETE /api/charts/<name>/<version>` need the `ADMIN` role, and so does
deleting a Docker manifest or tag. A `USER` account and a deploy token are refused with `401` and a
challenge, as for any credential a package client is not allowed to use. Operations that only change what a repository
advertises, and keep every file, need write access: `npm deprecate`, `npm dist-tag add` and `rm`,
`cargo yank`, NuGet unlist and relist, and `gem yank`.

Deploy tokens are scoped to a single repository, so use one to give a CI job or an external party
access to that repository without a user account. A deploy token reads and, unless it is read-only,
writes; it **never** manages, so a CI credential can publish but cannot delete what it published. Only create user accounts for people you trust
with every repository on the instance; to keep repositories apart between teams, run one Repsy
instance per team.

### Docker Registry Semantics

- **Manifests are content-addressed.** A manifest is stored once per image and digest and stays
  pullable by that digest, `docker pull repo/image@sha256:...`, whatever happens to the tags that
  point at it. The registry stores the `sha256` and the `sha512` digest of every manifest, so it is
  addressable by either, and it answers in the algorithm the client used: a reference by `sha512`
  gets a `sha512` `Docker-Content-Digest` back (on push, `GET` and `HEAD`, and in the push's
  `Location`), a tag or a `sha256` reference the `sha256` one. A manifest pushed by a digest never
  becomes a tag, and a `sha512` reference that is not the manifest's own digest is refused with
  `400 DIGEST_INVALID`. A manifest written by an earlier version is addressable by its `sha512`
  once the repair job below has recorded it (or the same bytes are pushed again).
- **A tag is a movable pointer.** Pushing a tag again with a different manifest (when the
  repository allows overriding) moves the pointer; the manifest it pointed at before stays stored
  and pullable by its digest. Pushing the manifest a tag already points at changes nothing.
  Deleting a tag in the web UI removes the pointer only, in the same way.
- **An image lives as long as it stores a manifest.** Deleting the last tag of an image does not
  delete the image: its manifests stay pullable by digest, so the web UI keeps listing it as "No
  tags", with how many untagged manifests it still stores and their size, and its page offers
  "Delete untagged manifests" and "Delete image". The image is removed automatically when its last
  manifest goes, whether by a protocol `DELETE` by digest or by "Delete untagged manifests"; the next
  push of that name creates it again.
- **Untagged manifests accumulate.** Nothing deletes a manifest automatically, so every override
  and every deleted tag leaves the previous manifest, and the layers only it used, on disk and in
  the repository's usage until you remove them with **"Delete untagged manifests"** in the
  repository settings, or one at a time with a protocol `DELETE` by digest (below). Deleting a whole
  image removes its manifests too (a manifest file that
  another image of the repository shares is kept until the last image that has it is gone).
- **"Delete untagged manifests"** (repository settings, needs the manage permission; API:
  `DELETE /api/docker/images/manifests/{repoName}/untagged`, optionally `?image=<name>`) deletes
  every manifest that no tag points to, directly or through a tag's index, together with its file,
  and refunds the disk usage right away. Those manifests stop being pullable by digest. It then
  deletes the layers that no manifest uses any more, which is what actually frees the space: the
  manifest files themselves are only kilobytes. The layer blobs are deleted in the background, and
  the usage drops as they go. Run it when nobody is pushing to the repository: a manifest pushed by
  digest whose tag or index has not arrived yet counts as untagged, and a client pushing an index
  right after would have to push that manifest again.
- **"Delete orphan layers"** in the repository settings deletes the layer blobs that no manifest
  uses (for example, left by a refused push). It does not touch manifests, so it frees nothing that
  an untagged manifest still uses: use "Delete untagged manifests" first, which runs this sweep
  itself, and "Delete orphan layers" for blobs no manifest ever used.
- **`DELETE /v2/<name>/manifests/<reference>` needs the MANAGE permission** (an admin user, the
  same as the operations of the repository settings page). A client that deletes manifests, such as
  `crane delete`, works with the credentials it was logged in with; a deploy token, which only reads
  and writes, is refused with `401`, and so is a user without the admin role.
  - By **digest** (`sha256:` or `sha512:`) it deletes the manifest and every tag that points at it,
    so neither the digest nor those tags can be pulled afterwards, as the distribution specification
    describes. It answers `202 Accepted`; a digest the image does not have is `404 MANIFEST_UNKNOWN`
    (a malformed one is `400 DIGEST_INVALID`). The manifests an index lists are not deleted with
    it: another index may still list them, so they stay, untagged, until they are deleted by their
    own digest or by "Delete untagged manifests". The manifest file is deleted (and its bytes
    released from the repository's usage) unless another image of the repository has the same
    manifest.
  - By **tag** it deletes that tag only, like deleting a tag in the web UI: the manifest stays
    pullable by its digest. It answers `202 Accepted`; an unknown tag is `404 MANIFEST_UNKNOWN`.
  - The layers a deleted manifest used are not deleted with it: "Delete orphan layers" (or "Delete
    untagged manifests", which runs that sweep) removes the ones no manifest uses any more.
- There is no `tags/list` or referrers API yet.

### Go Module Semantics

- **A module lasts as long as it has a version.** Deleting the last version of a Go module in the
  web UI (or with `DELETE /api/go/modules/{repoName}/versions`) also deletes the module: it leaves
  the module list and its stored files are moved to the trash, like the version's own. Publishing a
  version of that path again creates the module again. Deleting a module as a whole is the same
  operation for all of its versions. The disk usage of what was deleted is given back to the
  repository, and a deleted version is reported to the vulnerability scanner as deleted.
- **The Go proxy answers as it does for a module that was never published.** `@v/list` and `@latest`
  of a module without versions are `404` with a `text/plain` body, so the `go` command tries the
  next `GOPROXY` entry instead of taking an empty list as an answer.
- A delete and a publish of the same module take turns, so a publish that arrives while the last
  version is being deleted is stored, in a module that is created again, and never fails.

### Signed Maven Deploys

A Maven repository's key store (panel API, `/api/mvn/key-stores/{repoName}/public-keys`) can hold
armored OpenPGP public keys directly, in addition to the key-server hosts it already supports. When
a `.pom.asc` signature is verified, its registered public keys are consulted first, before any key
server. This lets a signature made with a key that is never published to a public server — a
company-internal key, a CI key, a freshly generated key — verify without network access. A
signature whose key is neither registered nor found on any allowed or default key server is
refused with `404` and nothing is stored. A key block a key server answered is remembered for ten
minutes, so a deploy with many signatures by one key asks the server once.

Two per-repository settings (repository settings page, or `PUT /api/repos/{repoName}/settings`
with `pgpVerifyAllSignaturesEnabled` / `pgpKeyServerLookupEnabled`; Maven repositories only) change
what is verified and where keys are looked up:

- **Default (`pgpVerifyAllSignaturesEnabled` off):** only the `.pom.asc` is verified. Any other
  signature (`.jar.asc`, `-sources.jar.asc`, `.module.asc`, ...) is stored as it is sent, and a
  version shows *Signed* when its POM signature verified.
- **Verify every signature (`pgpVerifyAllSignaturesEnabled` on):** every artifact `.asc` is verified
  against the file it signs before it is stored, exactly like the `.pom.asc`: a signature that does
  not match answers `422` and stores nothing. A version then shows *Signed* only when every file of
  it that a signing tool signs (the POM, the jar, every classifier jar, the `.module`, ... but not
  checksums, signatures or `maven-metadata.xml`) has a verified signature, so a partly signed
  release stays *Unsigned*. Files and signatures may arrive in **any order**, which a real
  `mvn deploy` needs: Maven uploads the files of a deploy in parallel, so the signature of a large
  file can reach Repsy before the file, or before the POM that registers the version. Such a
  signature is answered `200` and held: it is not stored, not served (`GET` answers `404`) and not
  charged, and it is verified when the file it signs arrives (or when the POM registers the
  version), then stored and recorded. If it does not verify, the file's upload fails with
  `422 pendingSignatureNotVerified` (a new file is taken back out of the repository, and the held
  signature is dropped so an upload of both again starts clean); if the signer's key cannot be found
  then, it fails with `404` and the signature stays held. A held signature that no file claims is
  deleted after `PENDING_SIGNATURE_TTL` (`PT24H`, see the configuration table); a re-sent signature
  replaces the one that is held. A signature that is not an OpenPGP signature at all is still
  refused at once with `422 artifactSignatureNotVerified`. A repository that does not verify every
  signature holds nothing: there a `.pom.asc` before its POM is refused with `404` as before.
  Uploading a new file, or storing a file again (with *Allow override*), makes the version
  *Unsigned* until that file's signature is uploaded and verified. For a snapshot only the files of
  its newest build count. Turning the setting on or off recomputes *Signed* of every existing
  version of the repository in the background, under the rule of the new setting (with it off, a
  version is *Signed* when its POM signature is verified; with it on, when every file has a
  verified signature). The settings request does not wait for it, so a large repository shows the
  new values within moments, not at once. A signature that was stored while the setting was off was
  never verified, so turning the setting on verifies it then, in that same background run, file by
  file and with the same key rules as an upload (registered keys first, key servers if the lookup
  is on): an honest publisher's versions stay *Signed*. A stored signature that does not verify, or
  whose key cannot be found, does not count and its version shows *Unsigned* until the file and
  signature are uploaded again (or the key is registered and the setting turned off and on again). Turning it off
  leaves held signatures alone: they are deleted when they expire.
- **Air-gapped registries (`pgpKeyServerLookupEnabled` off):** the repository consults its
  registered keys only. A signature made with a key that is not registered is refused at once with
  `404 artifactSigningKeyNotRegistered`, without contacting any key server (custom hosts,
  `keyserver.ubuntu.com` or `keys.openpgp.org`), so no network call and no timeout wait. Defaults to
  on.

### Maven *Allow override* and SNAPSHOTs

With *Allow override* off, a Maven repository refuses to store a file that already exists
(`403 artifactOverrideIsProhibited`), with one exception: a SNAPSHOT can always be deployed again.

- **Releases are immutable.** Any file of a release version that already exists is refused.
- **A timestamped SNAPSHOT build is immutable.** `mvn deploy` and Gradle write a new build
  (`lib-1.0-20260921.101010-2.jar`) on every deploy, so a redeploy never touches an existing file,
  and a file of an existing build is refused like a release file.
- **A non-unique SNAPSHOT is replaced.** sbt and Apache Ivy deploy `lib-1.0-SNAPSHOT.pom/.jar` (and
  their checksums, and classifier jars) under those literal names every time, so a repeated deploy
  replaces the files instead of adding a build. That is accepted, as it is on Nexus and Artifactory,
  and it is what makes the setting behave the same for every client.
- The *Snapshots* switch of the repository still decides: with it off, a SNAPSHOT is refused in
  both forms.

The panel shows the version of a SNAPSHOT that carries no `maven-metadata.xml` (what sbt and Ivy
leave) with the newest POM stored for it: the POM of the newest timestamped build, or the literal
`-SNAPSHOT` POM when there is no build. The same goes for a `maven-metadata.xml` that cannot be
parsed, and a manual vulnerability scan of such a SNAPSHOT scans its newest stored jar.

### Apache Ivy Clients

The web UI's Maven configuration dialog shows this setup with your repository URL and username filled
in. Apache Ivy reads a Maven repository through an `ibiblio` resolver in Maven-compatible mode; put
this in `ivysettings.xml` (`repo.example.com` is your `REPO_BASE_URL` host, `my-repo` the repository):

```xml
<ivysettings>
  <settings defaultResolver="repsy"/>
  <credentials host="repo.example.com"
               realm="Repsy"
               username="YOUR_USERNAME"
               passwd="YOUR_PASSWORD"/>
  <resolvers>
    <ibiblio name="repsy" m2compatible="true" root="https://repo.example.com/my-repo/"/>
  </resolvers>
</ivysettings>
```

- **Credentials:** Ivy looks them up by host (without the port) and realm. Repsy challenges with
  `Basic realm="Repsy"`, so set `realm="Repsy"`: a `<credentials>` without a realm (or with another
  one) sends no credentials and every request is answered `401`. Earlier versions used a longer
  realm name: if your `ivysettings.xml` still names that, change it to `Repsy` (the same goes for the
  realm in sbt's `Credentials(realm, host, user, password)`). With a
  [deploy token](#repository-access), use it as `passwd`; the `username` can be empty.
- **Publishing needs a POM.** A version is registered (and shows up in the web UI) by its POM, so list
  a `pom` artifact next to the jar in the `<publications>` of your `ivy.xml` and create it with
  `ivy:makepom`:

  ```xml
  <ivy-module version="2.0">
    <info organisation="com.example" module="my-lib" revision="1.0.0"/>
    <configurations>
      <conf name="default"/>
    </configurations>
    <publications>
      <artifact name="my-lib" type="jar" ext="jar" conf="default"/>
      <artifact name="my-lib" type="pom" ext="pom" conf="default"/>
    </publications>
  </ivy-module>
  ```

  ```xml
  <project name="my-lib" xmlns:ivy="antlib:org.apache.ivy.ant">
    <target name="publish">
      <ivy:settings file="ivysettings.xml"/>
      <ivy:resolve file="ivy.xml"/>
      <ivy:makepom ivyfile="ivy.xml" pomfile="build/my-lib.pom"/>
      <ivy:publish resolver="repsy" pubrevision="1.0.0" publishivy="false">
        <artifacts pattern="build/[artifact].[ext]"/>
      </ivy:publish>
    </target>
  </project>
  ```

- **`publishivy="false"`** on `ivy:publish` (it is an attribute of the task, not of the resolver).
  Otherwise Ivy also uploads its own ivy file, as `ivy-<revision>.xml` after the jar and the POM, which
  Repsy refuses with `400 invalidArtifactPath`: the build fails, although the jar and the POM are
  already stored.
- **Dependencies:** if the module has `<dependencies>`, give `ivy:makepom` a
  `<mapping conf="default" scope="compile"/>` (a child element of the task). Without a mapping every
  dependency is written to the POM as `<optional>true</optional>`, and a consumer does not resolve it
  transitively.
- **Republishing:** Ivy's `overwrite` defaults to `false`, so Ivy itself refuses to publish over a file
  that already exists ("destination file exists and overwrite == false"). Set `overwrite="true"` on
  `ivy:publish` to republish a SNAPSHOT; for a release, Repsy's *Allow override* setting still decides.
- **Reading:** the same `ivysettings.xml` resolves dependencies. A dependency on an artifact published
  like this can use the `default` configuration (`conf="default->default"`), as the dependency line on a
  version's page in the panel does, which asks for the jar only; a dependency without a `conf` resolves
  as well, because Repsy answers `404` for the `sources` and `javadoc` artifacts the artifact does not
  have and Ivy then skips them.
- **`maven-metadata.xml`:** Ivy, sbt and a raw `PUT` publish none, so there is no file to store. Repsy
  answers a `GET` or `HEAD` of the artifact-level `<group path>/<artifact>/maven-metadata.xml` (and of
  its `.md5`, `.sha1`, `.sha256` and `.sha512`) from the versions it has registered when no client
  stored one, so Maven `LATEST`, `RELEASE` and version ranges, Gradle `1.+` and sbt `latest.release`
  resolve for such artifacts. A file a client did store is served as it is, and it is kept complete:
  when a POM registers a version that the stored file lacks (an artifact that `mvn deploy` or Gradle
  published first and that Ivy or sbt then adds a version to), Repsy adds the version to the file,
  sorts the versions, recomputes `latest`, `release` and `lastUpdated` (`latest` is the highest
  version, not the last one deployed), rewrites the checksums that are stored next to it and deletes
  a stored `maven-metadata.xml.asc`, which no longer verifies. It only ever adds: a version the file
  lists that Repsy does not know is kept, and a file that cannot be parsed is left untouched. A
  later `mvn deploy` of the artifact finds the file and stores it with its own version added. Two
  clients publishing the same artifact at the same moment can still lose a version, when a client
  uploads a file it computed from a copy read before the other's version was added; the next POM
  registered for the artifact lists it again.
  Maven's plugin prefix lookup (`mvn hello:hi`, with the plugin's group in `pluginGroups`) reads the
  group-level `<group path>/maven-metadata.xml` instead, which `mvn deploy` uploads for a plugin but
  Gradle's `maven-publish`, sbt, Ivy and a raw `PUT` do not. Repsy answers a `GET` or `HEAD` of that file
  (and of its four checksums) too, from the plugins it has registered for the group, when none is stored:
  a `<plugins>` list with each plugin's name, prefix and artifactId, ordered by artifactId. The prefix
  is the plugin's own `goalPrefix`, which its jar names in `META-INF/maven/plugin.xml`, when the jar is
  stored before the POM (the order of Gradle's `maven-publish`; `mvn deploy` stores the file with the
  real prefix itself), and otherwise the one `maven-plugin-plugin` derives from the artifactId
  (`hello-maven-plugin` and `maven-hello-plugin` give `hello`, `maven-plugin-plugin` gives `plugin`).
  It is read once, when the POM registers, so a client that sends the POM before the jar gets the
  derived prefix until it uploads the POM again; the prefix of the artifact is that of its latest POM.
  A jar that cannot be read, has no descriptor, describes another artifact or names something that is
  not a usable prefix falls back to the derived one and never fails the upload. A stored group-level file is served as it is, and it is kept complete like the
  artifact-level one: when the POM of a plugin registers and the stored file does not list its
  artifactId (a plugin that `mvn deploy` published first and that Gradle, sbt or Ivy then adds a second
  plugin to), Repsy appends a `<plugin>` entry (name, prefix, artifactId) for it, and for any other
  plugin it has registered for the group that the file lacks, after the entries that are there, rewrites
  the checksums that are stored next to it and deletes a stored `maven-metadata.xml.asc`. It never
  changes or removes an entry, and leaves untouched a file it cannot parse and a file that lists
  versions and no plugins (the artifact-level file of the same path, see below). A plugin that sets its
  own `goalPrefix` and is published by `mvn deploy` into a group whose file is stored already can end up
  listed twice, under the prefix derived from its artifactId (added when its POM arrived, before its
  jar, in Maven's order) and under its own (merged in by Maven afterwards); Maven finds the plugin by either. The path of that file has the
  shape of an artifact-level one (`com/acme/tools/maven-metadata.xml` is both the artifact `tools` of
  `com.acme` and the group `com.acme.tools`), so the artifact-level answer comes first and the
  group-level one is given only when no artifact of that name is registered.
  Nothing generated is stored: it is not in the directory listing and is never signed (`.asc` is a
  `404`). The version-level `<version>-SNAPSHOT/maven-metadata.xml` is not generated (RPS-1438): Maven
  and Gradle publish it themselves for a unique SNAPSHOT, and Ivy and sbt publish a non-unique one under
  its literal `-SNAPSHOT` file names, which Maven, Gradle and Ivy resolve without it, so no client
  needs Repsy to write it.

### Authenticating from CI

Prefer a [deploy token](#repository-access) for CI jobs, build servers and anything else that
sends credentials on every request (a Maven build that resolves hundreds of dependencies, a
`docker pull` of an image with many layers). A deploy token is checked with a single fast hash. A
user password is stored with BCrypt, which is slow on purpose, so an HTTP Basic request with a
username and password costs one BCrypt verification (tens of milliseconds of CPU) unless Repsy has
already seen that password succeed.

Repsy remembers successful Basic password checks for `BASIC_AUTH_CACHE_TTL_SECONDS` (5 minutes by
default), which removes that cost for clients that send the same credentials repeatedly. In a
local measurement of 300 authenticated API requests, this took a request from about 47 ms to under
2 ms, and the throughput of 8 concurrent clients from about 120 to about 1,700 requests per second.
The cache holds only a keyed digest, never a password. A changed password, a deleted user or a
changed role takes effect on the next request, and a wrong password or an unknown username is
never remembered, so it is checked and answered exactly as before. Set `BASIC_AUTH_CACHE_ENABLED`
to `false` to turn the cache off.

The cache does not make a *failed* login cheaper, so Repsy limits those instead. Every client, told
apart by its address (see [Reverse Proxy](#reverse-proxy)), may make `AUTH_THROTTLE_MAX_FAILURES`
failed password checks (20 by default) per `AUTH_THROTTLE_WINDOW_SECONDS` (60 seconds by default).
The repository ports, the `/api` routes that take Basic credentials and the web UI login all count
into the same number. After that the client's next password check is answered with
`429 Too Many Requests` and a `Retry-After` header (the seconds left in the window) without spending
a BCrypt verification, until the window ends. That caps what a flood of wrong credentials costs at
about a second of CPU per client and minute. Set `AUTH_THROTTLE_ENABLED` to `false` to turn the
limit off.

- **What counts:** a failed password verification (a wrong password, or a username that does not
  exist) and a credential sent as a `Bearer` value that Repsy does not recognise: a deploy token
  that was revoked, rotated or belongs to another repository, or a forged token (npm's
  `_authToken`, a NuGet API key, a Cargo or Ruby token). A validly signed token that has merely
  expired is recognised and does not count (it is answered `sessionExpired`), so a long `docker
  push` whose token runs out does not spend the budget of everyone behind the same address.
  All of them count the same and are refused the same way (`401 unAuthorized`), so the limit never
  reveals which usernames exist, and it is keyed on the client, never on the username. A request
  that succeeds, a valid deploy token, a valid bearer token, a recognised token that is refused
  (revoked or read-only for a write, or without the admin role for a management call) and a
  request that carries no username do not count, and a success does not reset the count: only the
  end of the window does.
- **A stale token in CI:** a job that keeps sending a rotated or revoked token (an old
  `NPM_TOKEN`, an outdated `--api-key`) spends the same budget as a wrong password. `npm install`
  sends many requests at once, so one stale token can use up the 20 failures within a second and
  block the address for the rest of the window, until the token is fixed. Valid tokens keep
  working while an address is blocked. Rotate the secret in the job as soon as you revoke it in
  Repsy.
- **Shared addresses:** many users behind one address (a company NAT, shared CI egress) share one
  count. A password Repsy remembers (see above), a valid deploy token and a valid bearer token cost no verification and keep
  working for a client that is over the limit, so a CI job is not locked out by a neighbour that
  sends wrong passwords. A client that keeps sending guesses after it was blocked (ten times the
  limit) loses that as well until its window ends. Raise `AUTH_THROTTLE_MAX_FAILURES` if a shared
  address regularly trips the limit.
- **Package managers:** none of them is sent a `WWW-Authenticate` challenge with the 429, so none
  asks for credentials again in a loop. Maven and npm retry with a backoff; pip, twine, cargo, go,
  NuGet, gem and bundler show the HTTP error; Docker and Helm print `toomanyrequests`. Fix the
  credentials in the job and wait for the `Retry-After` time.
- **IPv6:** the client is the address's `/64` network, not the full address, since a single
  subscriber or site normally holds a whole `/64`. Two addresses of the same `/64` share one count.

A rate limit in your reverse proxy can be used in addition: it can also cap the request rate as a
whole, which this limit does not do.

For detailed information on creating repositories, managing deploy tokens, and using different protocols (Golang, Cargo(Rust), Maven, npm, PyPI, Docker), see the [documentation](https://docs.repsy.io).

## Troubleshooting

### Common Issues

**Port already in use:**
```bash
# Check what's using port 8080 or 9090
lsof -i :8080
lsof -i :9090
```

**Database connection failed:**
```bash
# Check if PostgreSQL is running
docker ps | grep postgres

# Check PostgreSQL logs
docker logs repsy-postgres
```

**Admin user not created:**
```bash
# Check application logs
docker logs repsy

# Verify ADMIN_INITIAL_PASSWORD was set
docker exec repsy env | grep ADMIN
```

**Can't login:**
- Verify `ADMIN_INITIAL_PASSWORD` was set before the first startup
- <a id="forgot-admin-password"></a>**Forgot admin password?** Create an empty file named after the user in the password reset
  directory, from inside the container. No database access is needed, and it works for any user, not
  only for an admin. In the Docker image the directory is `/app/data/password-reset`:
  ```bash
  # Docker
  docker exec repsy touch /app/data/password-reset/admin
  docker logs repsy 2>&1 | grep "New password"

  # Kubernetes
  kubectl exec deploy/repsy -- touch /app/data/password-reset/admin
  kubectl logs deploy/repsy | grep "New password"
  ```
  Within a few seconds (`PASSWORD_RESET_MARKER_POLL_INTERVAL`) Repsy removes the file, generates a
  new random password for that user, revokes every session and refresh token of the account, and
  logs one line at `WARN`:
  ```
  Password of user admin has been reset by the marker file /app/data/password-reset/admin. New password: <password>
  ```
  Copy the password from the log, sign in and change it under Profile: the log may be shipped
  elsewhere. The file is removed before the password is changed, so a marker is applied only once.
  The file name must be a valid username (lower-case letters, digits, `_` and `-`, 3 to 25
  characters), and its content is never read. A marker for a user that does not exist is removed
  with a warning. Symlinks and directories are ignored. Outside the image, or with a different
  `PASSWORD_RESET_MARKER_DIR`, use the directory you configured (`<STORAGE_BASE_PATH>/password-reset`
  by default).

  **While Repsy is stopped** (for example, with the embedded H2 database, whose file only one process
  can open), put the marker on the data volume and start Repsy: the directory is read once at
  startup. Create it with the Repsy image itself, not with a root shell such as `alpine`: the image
  runs as `appuser`, so the marker (and the directory, if the volume was created by an image that
  predates this feature and does not have it yet) belongs to the user Repsy runs as. A directory
  created by root cannot be emptied by Repsy: it logs the error `Could not apply the password reset marker`
  and leaves the file in place.
  ```bash
  docker stop repsy
  docker run --rm -v repsy-data:/app/data --entrypoint sh repo.repsy.io/repsy/os/repsy:latest \
    -c 'mkdir -p /app/data/password-reset && touch /app/data/password-reset/admin'
  docker start repsy
  docker logs repsy 2>&1 | grep "New password"
  ```
  If the log shows more than one `New password` line for the same user (a marker and an emptied
  hash, see below, applied at the same startup), use the last one.

  **If the marker does nothing:** check that `PASSWORD_RESET_MARKER_ENABLED` is not `false`, and that
  the directory is writable by the user Repsy runs as (`appuser` in the image; a bind-mounted
  `/app/data` owned by root makes Repsy log `Could not create the password reset marker directory`
  at startup; a marker Repsy cannot delete, for example on a read-only volume, is not applied and is
  logged at `ERROR` as `Could not apply the password reset marker`, once per file, not on every
  poll). Anyone who can write to that directory can lock an account out (the account then has a
  password only the log holds), which is why it is a directory of its own, outside the protocol
  storage tree in the Docker image. Set `PASSWORD_RESET_MARKER_ENABLED=false` if you do not want it.

  **Alternative, without the marker directory (older images):** reset the password by setting the
  hash to an empty string in the database (`hash` is `NOT NULL`, so `NULL` is rejected):
  ```sql
  -- Connect to PostgreSQL
  docker exec -it repsy-postgres psql -U repsy -d repsy

  -- Reset the password of every admin
  UPDATE users SET hash = '' WHERE role = 'ADMIN';

  -- Or of a single admin
  UPDATE users SET hash = '' WHERE role = 'ADMIN' AND username = 'admin';

  -- Exit and restart the application
  \q
  docker restart repsy

  -- Check logs for the new random password
  docker logs repsy | grep "Admin password"
  ```
  On the next startup the application generates a new random password for every admin whose hash
  is empty and logs it, one line per admin.

  **On the embedded H2 database** there is no `psql`, and the database file is locked while the
  application runs, so stop it first and run the H2 shell once against the database file of your
  `DB_URL` (`/app/data/repsy` for the default URL in the
  [environment variables](#environment-variables)) with the `DB_USERNAME` and `DB_PASSWORD` the
  application uses (`repsy` / `repsy123` by default):
  ```bash
  docker stop repsy

  docker run --rm -v repsy-data:/app/data --entrypoint java repo.repsy.io/repsy/os/repsy:latest \
    -Dloader.main=org.h2.tools.Shell -cp /app/app.jar \
    org.springframework.boot.loader.launch.PropertiesLauncher \
    -url "jdbc:h2:file:/app/data/repsy;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" \
    -user repsy -password repsy123 \
    -sql "UPDATE users SET hash = '' WHERE role = 'ADMIN'"

  docker start repsy

  # Check logs for the new random password
  docker logs repsy | grep "Admin password"
  ```
  Keep `MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE` in that URL even if your `DB_URL` differs: the tables
  have lower-case names, and without `DATABASE_TO_LOWER=TRUE` the statement fails with
  `Table "USERS" not found`. To reset a single admin, append `AND username = 'admin'` to the
  statement.

**Vulnerability scans failing with 401/500:**
- Check that `TRIVY_SCANNER_API_KEY` (repsy-backend) and `SCANNER_API_KEY` (`repsy-scanner-trivy`) are set to the exact same value — a mismatch causes the scanner to reject requests.

**Docker scans failing:**
- Remember that `DOCKER_INTERNAL_REGISTRY_BASE_URL` is resolved from the **scanner container's** point of view, not the backend's — it must never be `localhost`. Use `host.docker.internal` (hybrid topology, backend on host) or the backend's service name (e.g. `http://repsy:9090`, full-compose topology) instead.

### Logs

```bash
docker logs -f repsy
docker logs -f repsy-postgres
```

### Reset Everything

```bash
# Stop and remove container
docker rm -f repsy

# Remove the named volume: it holds the H2 database and the artifact files (/app/data/storage)
docker volume rm repsy-data

# Start fresh (H2 example)
docker run -d \
  --name repsy \
  -p 8080:8080 \
  -p 9090:9090 \
  -e ADMIN_INITIAL_PASSWORD=YourSecurePassword123 \
  -v repsy-data:/app/data \
  repo.repsy.io/repsy/os/repsy:latest
```

## Development

This section is for developers who want to contribute to or modify Repsy.

### Prerequisites

- **Java**: JDK 25
- **Spring Boot**: 4.0.5
- **PostgreSQL**: 18
- **Angular**: 21
- **Maven**: 3.9.7 or higher
- **Node.js** 24.x (>=24.0.0 <25.0.0)

### Project Structure

```
repsy/
├── repsy-backend/          # Spring Boot backend
│   ├── src/main/java/         # Java source code
│   ├── src/main/resources/    # Configuration files
│   └── src/test/              # Unit (*Test) and integration (*IT) tests
├── repsy-frontend/         # Angular frontend
│   ├── src/app/               # Angular components
│   └── src/assets/            # Static assets
├── libs/                      # Shared libraries
│   ├── protocol-router/       # Protocol routing
│   ├── multiport/             # Multi-port handling
│   └── storage/               # Storage layer
└── repsy-protocols/           # Protocol implementations
```

### Development Setup

```bash
# 1. Start PostgreSQL for development
docker run -d \
  --name repsy-postgres \
  -e POSTGRES_DB=repsy \
  -e POSTGRES_USER=repsy \
  -e POSTGRES_PASSWORD=repsy_123 \
  -p 5432:5432 \
  postgres:18

# 2. Build backend
mvn clean install -DskipTests

# 3. Run backend in development mode
cd repsy-backend
mvn spring-boot:run

# 4. In another terminal, run frontend
cd repsy-frontend
pnpm install
pnpm start
```

Access development environment:
- **Frontend (Web UI)**: http://localhost:4200 (with hot reload)
- **Backend API**: http://localhost:8080
- **Repository Operations**: http://localhost:9090
- To inspect the embedded H2 database directly, stop the app and open the database file with the H2 shell (see "Troubleshooting" below) — the TCP server (`H2_TCP_SERVER_ENABLED=true`) only binds inside the container/host loopback, so it is not reachable from an external client and is not a supported inspection path


### Building for Production

```bash
# Build Docker image (run from repo root)
docker build -f Dockerfile -t repsy:latest .
```

### Running Tests

```bash
# Unit tests only (*Test.java) - no Docker needed
mvn test

# Unit + integration tests (*IT.java) - requires a running Docker daemon
mvn verify
```

Unit tests are named `*Test` and run in Surefire; integration tests are named `*IT` and run in Failsafe. Integration tests use [Testcontainers](https://testcontainers.com/) to start a real PostgreSQL 18 container.

To run a single integration test class:

```bash
mvn verify -pl repsy-backend -am -Dit.test=ProfileControllerIT
```

### Code Style

- **Java**: Follow Google Java Style Guide
- **TypeScript/Angular**: Follow Angular Style Guide
- **Commits**: Use conventional commits format

### Database Migrations

Migrations are managed with Flyway in `src/main/resources/db/migration/`.

```bash
# File format: V{version}__{description}.sql
# Example: V0002__add_user_roles.sql
```

### Contributing

1. Fork the repository
2. Create feature branch: `git checkout -b feature/amazing-feature`
3. Commit changes: `git commit -m 'feat: add amazing feature'`
4. Push to branch: `git push origin feature/amazing-feature`
5. Open Pull Request

**Contribution Guidelines:**
- Write tests for new features
- Update documentation
- Follow existing code style
- Keep commits atomic and well-described

## License

This project is licensed under the Apache License, Version 2.0 - see the [LICENSE.txt](LICENSE.txt) file for details.

## Support

- **Documentation**: [docs.repsy.io](https://docs.repsy.io)
- **Issues**: [GitHub Issues](https://github.com/repsyio/repsy/issues)

---

Developed using Spring Boot and Angular
