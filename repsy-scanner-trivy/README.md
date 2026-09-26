# repsy-scanner-trivy

Standalone Trivy vulnerability scanner adapter service. Exposes a single `POST /scan`
endpoint (multipart: `file` + `repoType`/`artifactName`/`artifactVersion` form fields),
protected by a shared API key (`X-Scanner-Api-Key` header). `GET /health` is unauthenticated.

## Published image

Every Repsy Open Source release publishes the scanner next to the application image, under the same
tags (a release tag without the leading `v`, such as `26.10.0`, and `latest`):

```bash
docker pull repo.repsy.io/repsy/os/repsy-scanner-trivy:26.10.0
```

Run it with the application image of the **same release** (`repo.repsy.io/repsy/os/repsy`). The
HTTP contract between them is not versioned and `GET /health` reports no version, so a mixed pair is
not supported. [`../examples/docker-compose.scanner.yml`](../examples/docker-compose.scanner.yml)
runs both, PostgreSQL and the `trivy-cache` volume (mounted at `/home/appuser/.cache/trivy`, where
Trivy keeps its vulnerability databases across container recreation). On the application side set
`SECURITY_SCANNER=enabled`, `TRIVY_SCANNER_BASE_URL`, `TRIVY_SCANNER_API_KEY` (the same value as this
service's `SCANNER_API_KEY`) and `DOCKER_INTERNAL_REGISTRY_BASE_URL`; see the root
[README](../README.md#environment-variables).

## What a scan covers

A scan covers what the submitted artifact **contains**; it does not resolve what the artifact declares.

- **Files (Maven, npm, PyPI).** The service unpacks a `.tgz`/`.tar.gz` or a wheel (`.whl`) into a temporary
  directory (any other file, such as a jar, is scanned as it is) and runs `trivy rootfs --format json` on it.
  `rootfs` runs Trivy's analyzers for *installed* packages: `node_modules/*/package.json`, jars (nested jars
  included), Python `.dist-info` metadata. The analyzers for lock files (`package-lock.json`, `yarn.lock`,
  `pnpm-lock.yaml`) and for dependency resolution (a `pom.xml`) only run under `trivy fs`, which this service does
  not use.
- **What follows.** An npm tarball is scanned for the packages it bundles, not for its `dependencies`; a thin jar
  is scanned as itself, not for the dependencies of its POM; a wheel is scanned for its own metadata, not for its
  `Requires-Dist`. A package that declares vulnerable dependencies without bundling them is scanned clean.
- **Docker.** An image is not uploaded: the service runs `trivy image` on the reference it is given and pulls the
  image itself, so all its layers are scanned.
- **`npm audit`.** The application answers `npm audit` from the findings of these scans, so it reports an advisory
  only for a package and version that a scanned tarball of that repository bundled (see the root
  [README](../README.md#auditing-npm-packages)).

## Local build & run

```bash
docker build -t repsy-scanner-trivy:local repsy-scanner-trivy

docker run -p 8090:8090 \
  -e SCANNER_API_KEY=<key> \
  repsy-scanner-trivy:local
```

The container listens on port `8090` by default (`SERVER_PORT` env var to override).

### Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `SCANNER_API_KEY` | yes | — | Shared secret checked against the `X-Scanner-Api-Key` header |
| `SERVER_PORT` | no | `8090` | HTTP port the service listens on |
| `TRIVY_BINARY_PATH` | no | `trivy` | Path to the `trivy` binary (already baked into the image) |
| `TRIVY_TIMEOUT_SECONDS` | no | `300` | Max time to wait for a single `trivy` subprocess run |
| `SCANNER_WORKER_COUNT` | no | `1` | Number of scan jobs processed concurrently |
| `SCANNER_JOB_RETENTION_MINUTES` | no | `60` | How long a finished job's status/result stays queryable via `GET /scan/{id}` |
| `SCANNER_JOB_RETENTION_CHECK_INTERVAL_MS` | no | `600000` | How often the retention sweep runs to evict expired jobs |
| `TRIVY_DB_REPOSITORY` | no | `ghcr.io/aquasecurity/trivy-db:2,mirror.gcr.io/aquasec/trivy-db:2` | Comma-separated OCI repositories the vulnerability database is downloaded from, tried in order (set it to a mirror on a network without access to `ghcr.io`) |
| `TRIVY_JAVA_DB_REPOSITORY` | no | `ghcr.io/aquasecurity/trivy-java-db:1,mirror.gcr.io/aquasec/trivy-java-db:1` | Same, for the Java (Maven) database |
| `SHUTDOWN_TIMEOUT_SECONDS` | no | `300` | How long a graceful shutdown waits for running scans |

### Try it

```bash
curl -X POST http://localhost:8090/scan \
  -H "X-Scanner-Api-Key: <key>" \
  -F "scanId=$(uuidgen)" \
  -F "repoType=MAVEN" \
  -F "artifactName=org.apache.logging.log4j:log4j-core" \
  -F "artifactVersion=2.14.1" \
  -F "file=@log4j-core-2.14.1.jar;type=application/octet-stream"
```
