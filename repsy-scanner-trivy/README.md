# repsy-scanner-trivy

Standalone Trivy vulnerability scanner adapter service. Exposes a single `POST /scan`
endpoint (multipart: `file` + `repoType`/`artifactName`/`artifactVersion` form fields),
protected by a shared API key (`X-Scanner-Api-Key` header). `GET /health` is unauthenticated.

## Local build & run

```bash
docker build -t repsy-scanner-trivy:local repsy-os/repsy-scanner-trivy

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
