# repsy-scanner-trivy

Standalone Trivy vulnerability scanner adapter service. Its endpoints are `POST /scan` (asynchronous;
multipart: `file` + `repoType`/`artifactName`/`artifactVersion` form fields) with `GET /scan/{scanId}`,
`POST /advisories` (a synchronous lookup of name/version pairs, see [below](#advisory-lookup-post-advisories))
and `GET /status` (the Trivy version and the age of its databases). All of them are protected by a shared API
key (`X-Scanner-Api-Key` header) except `GET /health`, which is unauthenticated and says nothing but
`{"status":"ok"}`.

## Published image

Every Repsy Open Source release publishes the scanner next to the application image, under the same
tags (a release tag without the leading `v`, such as `26.10.0`, and `latest`):

```bash
docker pull repo.repsy.io/repsy/os/repsy-scanner-trivy:26.10.0
```

Run it with the application image of the **same release** (`repo.repsy.io/repsy/os/repsy`). The
HTTP contract between them is not versioned (`GET /status` reports the version of Trivy, not of the
scanner), so a mixed pair is not supported. [`../examples/docker-compose.scanner.yml`](../examples/docker-compose.scanner.yml)
runs both, PostgreSQL and the `trivy-cache` volume (mounted at `/home/appuser/.cache/trivy`, where
Trivy keeps its vulnerability databases across container recreation). On the application side set
`SECURITY_SCANNER=enabled`, `TRIVY_SCANNER_BASE_URL`, `TRIVY_SCANNER_API_KEY` (the same value as this
service's `SCANNER_API_KEY`) and `DOCKER_INTERNAL_REGISTRY_BASE_URL`; see the root
[README](../README.md#environment-variables).

## What a scan covers

A scan covers what the submitted artifact **contains**; it does not resolve what the artifact declares.

- **Files (Maven, npm, PyPI).** The service unpacks a `.tgz`/`.tar.gz` or a wheel (`.whl`) into a temporary
  directory (any other file, such as a jar, is scanned as it is) and runs `trivy rootfs --format json` on it.
  `rootfs` runs Trivy's analyzers for *installed* packages: the package's own metadata files (`package/package.json`
  for npm, `package/PKG-INFO` or `*.dist-info` for PyPI), bundled packages in `node_modules/*/package.json`, nested
  jars in archives. The analyzers for lock files (`package-lock.json`, `yarn.lock`, `pnpm-lock.yaml`) and for
  dependency resolution (a `pom.xml`) only run under `trivy fs`, which this service does not use.
- **What follows.** An npm tarball is scanned for its own package and the packages it bundles, not for its declared
  `dependencies` or `devDependencies` (lock files are ignored); a thin jar is scanned as itself, not for the
  dependencies of its POM; a wheel is scanned for its own metadata, not for its declared `Requires-Dist`. A package
  that declares vulnerable dependencies without bundling them is scanned clean.
- **Gaps.** A scan only sees versions that were pushed while scanning was enabled and not turned off in the repo
  settings. Findings are frozen at the time of the scan and do not update when the vulnerability database learns a new
  advisory. An audit searches only the repository it is aimed at, so versions installed from elsewhere (or in Cloud,
  proxied packages) are not scanned.
- **Docker.** An image is not uploaded: the service runs `trivy image` on the reference it is given and pulls the
  image itself, so all its layers are scanned.
- **`npm audit`.** The application answers `npm audit` from the findings of these scans (see the root
  [README](../README.md#auditing-npm-packages)), and the advisory lookup below answers for pairs no scan saw.

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
| `TRIVY_CACHE_DIR` | no | `$HOME/.cache/trivy` (`/home/appuser/.cache/trivy` in the image) | Where Trivy keeps its databases. The service passes it to every trivy run as `--cache-dir` and reads the dates of the databases from it. Mount a volume here, and leave room for a second copy of the databases while a refresh runs (about 3 GB, see "Database refresh") |
| `TRIVY_DB_REFRESH_INTERVAL` | no | `PT12H` | How often the databases are refreshed (an ISO-8601 duration; the first refresh after a start is one interval later, the start-up download comes first). A refresh downloads only when a database is past its `NextUpdate` (Trivy publishes a database every 6 hours and gives it a `NextUpdate` a day ahead) |
| `TRIVY_ADVISORY_TIMEOUT_SECONDS` | no | `30` | Max time a `POST /advisories` lookup waits for its `trivy` run (answer `504` after that) |
| `SCANNER_ADVISORY_CONCURRENCY` | no | `2` | How many lookups may run or wait for the database at once; the next one gets a `503` |
| `SCANNER_ADVISORY_MAX_WAIT_SECONDS` | no | `5` | How long a lookup waits for a running scan or a database switch to end before it gets a `503` |
| `TRIVY_JAVA_DB_REPOSITORY` | no | `ghcr.io/aquasecurity/trivy-java-db:1,mirror.gcr.io/aquasec/trivy-java-db:1` | Same, for the Java (Maven) database |
| `SHUTDOWN_TIMEOUT_SECONDS` | no | `300` | How long a graceful shutdown waits for running scans |

### Advisory lookup: `POST /advisories`

A synchronous answer to "which advisories does the vulnerability database hold for these exact (name, version)
pairs", for `npm audit`: the application sends the pairs of an audit and merges the answer with the findings of the
scans it stored. It uses only the database that is already on disk (`trivy sbom --offline-scan --skip-db-update
--skip-java-db-update`, on a CycloneDX 1.5 file written from the pairs), never downloads and never uses the network,
and takes about a second for 20,000 pairs. The contract is not versioned, like `POST /scan`; the scanner and the
application ship together.

Request: `Content-Type: application/json`, header `X-Scanner-Api-Key`.

```json
{"ecosystem": "npm", "packages": [{"name": "lodash", "version": "4.17.20"}, {"name": "@babel/traverse", "version": "7.20.0"}]}
```

- `ecosystem` must be `"npm"` (the only one for now).
- `packages`: at most **20,000** entries (the limit of an npm audit), repeated pairs are looked up once. `name` is an
  npm package name (`name` or `@scope/name`, at most 214 characters) and `version` at most 256 characters; neither
  may be blank or hold a control character. A version need not be valid semver: one that Trivy cannot read finds
  nothing. An empty list is answered at once with no findings.
- The body is at most **10 MiB** (`10485760` bytes), checked from `Content-Length` and again while it is read.

Answer `200`:

```json
{
  "dbUpdatedAt": "2026-09-26T19:03:57.371914884Z",
  "scannerVersion": "0.66.0",
  "findings": [
    {"cveId": "CVE-2021-23337", "severity": "HIGH", "packageName": "lodash", "packageVersion": "4.17.20",
     "fixedVersion": "4.17.21", "description": "...", "referenceUrl": "...", "fixStatus": "FIXED",
     "cvssScore": 7.2, "cvssVector": "CVSS:3.1/..."}
  ]
}
```

- `dbUpdatedAt` is when the vulnerability database was published (Trivy's `UpdatedAt`, an ISO-8601 instant); it
  is `null` only for an empty `packages` list. `scannerVersion` is the Trivy release, `null` when the binary does not
  answer.
- `findings` are `ScannerFinding`s as in `GET /scan/{scanId}`. `packageName` and `packageVersion` identify the pair
  (the same string that was sent); a pair with several advisories has several findings, a pair with none has none.
- A private package whose name equals a public one gets the public package's advisories, as it would on npmjs.org.

Errors (the body is `{"message": "..."}`, except for `401`):

| Status | When |
|---|---|
| `400` | The body is not JSON, `ecosystem` is not `npm`, `packages` is missing, or a pair is malformed |
| `401` | The API key is missing or wrong (`{"message":"unauthorized"}`) |
| `413` | More than 20,000 pairs, or a body over 10 MiB |
| `415` | The `Content-Type` is not `application/json` |
| `503` | No database has been downloaded yet; `SCANNER_ADVISORY_CONCURRENCY` lookups are already in progress; or a scan or a database switch keeps the database for longer than `SCANNER_ADVISORY_MAX_WAIT_SECONDS` (see below). Retry later |
| `504` | The lookup took longer than `TRIVY_ADVISORY_TIMEOUT_SECONDS` |
| `500` | Trivy failed in another way (`{"message":"Advisory lookup failed"}`; the cause is in the log) |

The caller should treat every non-`200` answer as "no lookup this time" and go on with what it has.

### Status: `GET /status`

Behind the API key, unlike `GET /health` (which is exempt so that probes work, and so must not say which Trivy
version runs or how old its databases are):

```json
{"trivyVersion": "0.66.0", "dbUpdatedAt": "2026-09-26T19:03:57.371914884Z",
 "dbDownloadedAt": "2026-09-26T22:39:09.416486505Z", "javaDbUpdatedAt": "2026-09-26T01:07:27.74709307Z"}
```

Every field is `null` while unknown (no answer of the binary, no database yet). `dbUpdatedAt` and `javaDbUpdatedAt`
are the `UpdatedAt` of Trivy's `metadata.json` of the two databases, `dbDownloadedAt` its `DownloadedAt`.

### Database refresh and how trivy runs share the databases

The databases are downloaded once at start-up (readiness holds until then) and then by a scheduled refresh every
`TRIVY_DB_REFRESH_INTERVAL`, because a lookup never downloads and would otherwise go stale without scan traffic. A
scan on its own also downloads a database that is past its `NextUpdate`. A refresh that fails (no network, the
registry answers an error) logs a warning and leaves the databases as they are; if there is no vulnerability
database at all, a retry runs every 5 minutes. `TRIVY_DB_REPOSITORY` and `TRIVY_JAVA_DB_REPOSITORY` apply.

Trivy does not coordinate its own runs on one cache directory (measured with Trivy 0.66.0), so the service does:

- **A download replaces the database in place.** It deletes `metadata.json`, rewrites `trivy.db` and writes the
  metadata again only at the end. A run that starts in between fails ("`--skip-db-update cannot be specified on the
  first run`"), and a download that dies half way leaves no usable database. The scheduled refresh therefore
  downloads into an empty `refresh-staging` directory inside the cache directory (which needs room for a second
  copy of the databases while it runs, about 3 GB), and only when both databases are complete moves them into place, in
  a moment during which no other trivy run is active. A failed refresh changes nothing. The start-up download, which
  runs before the service accepts anything, is left to Trivy, as before.
- **A scan keeps the database open for its whole run**, and another trivy process that opens it fails after a second
  ("vulnerability database may be in use by another process"). So a lookup and a scan never run at the same time.
  Scans (which may overlap each other, as before) go first: a lookup waits at most `SCANNER_ADVISORY_MAX_WAIT_SECONDS`
  for a running scan and then answers `503`, and a waiting scan holds back new lookups. Lookups may overlap each other.
  During a scan of a large Docker image the application therefore gets no lookups (it falls back to the stored
  findings); a scan of a package takes seconds.
- A database switch waits for the running scans and lookups, and holds back new ones, for the moment it takes to
  rename the files (seconds on a large database, as the disk flushes the new file).

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

```bash
curl -X POST http://localhost:8090/advisories \
  -H "X-Scanner-Api-Key: <key>" -H "Content-Type: application/json" \
  -d '{"ecosystem":"npm","packages":[{"name":"lodash","version":"4.17.20"}]}'

curl -H "X-Scanner-Api-Key: <key>" http://localhost:8090/status
```

## Tests

The service is not part of the root Maven reactor, so `mvn verify` at the repository root does not run its
tests. They need no Docker and no network (a fake trivy script and a fake command runner stand in for the binary):

```bash
git submodule update --init      # the Checkstyle configuration lives in core/
mvn -f repsy-scanner-trivy/pom.xml verify
```
