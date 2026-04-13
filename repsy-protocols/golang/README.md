# Go Module Proxy — Repsy

Repsy implements the [GOPROXY protocol](https://go.dev/ref/mod#goproxy-protocol), allowing you to host private Go modules without a VCS. Modules are uploaded as plain `.mod` + `.zip` file pairs via HTTP PUT and fetched by the standard `go` toolchain through `GOPROXY`.

---

## Table of Contents

- [Module Path Convention](#module-path-convention)
- [Uploading a Module](#uploading-a-module)
- [Consuming a Module](#consuming-a-module)
- [CI/CD Configuration](#cicd-configuration)
- [Deploy Tokens](#deploy-tokens)
- [Checksum Database](#checksum-database)
- [Known Limitations](#known-limitations)
- [Troubleshooting](#troubleshooting)

---

## Module Path Convention

A Go module path must contain at least one dot in its first path segment (a Go toolchain requirement). Beyond that, the domain you choose determines whether Go attempts VCS discovery before consulting the proxy.

**Recommended patterns:**

```
io.repsy/hello-world
io.repsy/platform/auth
mycompany.dev/payments
```

**Avoid:**

```
example.com/anything     # IANA reserved, but returns HTTP 200 — Go discovers a VCS there
github.com/myorg/...     # public, always reachable
localhost/...            # no dot in first segment — Go rejects it
mymodule/...             # no dot in first segment — Go rejects it
```

**The rule:** the domain must not return HTTP 200 when Go sends `GET <domain>?go-get=1`. If it does, Go continues VCS discovery even when `GOPROXY` is set. Domains that do not resolve at all (e.g. `.internal` TLD, `.dev` without a DNS record) are safe.

---

## Uploading a Module

Repsy does not run `go mod` commands. You build the zip locally and upload it with a single `curl`.
Repsy extracts `go.mod` from the zip, validates it, and stores both files.

### 1. Prepare the zip

The zip must follow the Go module zip layout exactly:

```
io.repsy/hello-world@v1.2.0/go.mod
io.repsy/hello-world@v1.2.0/hello-world.go
io.repsy/hello-world@v1.2.0/internal/calc.go
```

Rules:
- Every entry must be prefixed with `{modulePath}@{version}/`
- **No empty directories** — zip entries must be files only
- Do **not** use `zip -r` (it adds directory entries). Add files individually:

```bash
MODULE="io.repsy/hello-world"
VERSION="v1.2.0"

zip module.zip \
  "${MODULE}@${VERSION}/go.mod" \
  "${MODULE}@${VERSION}/hello-world.go" \
  "${MODULE}@${VERSION}/internal/calc.go"
```

Or with `find` for larger modules:

```bash
find "${MODULE}@${VERSION}" -type f | xargs zip module.zip
```

### 2. Upload

Use `-T` for the upload. The `Content-Sha256` header is optional but recommended — the server
verifies the checksum and rejects the upload if it does not match.

```bash
REPSY_URL=https://repsy.example.com
REPO=my-go-repo
USER=alice
PASS=secret          # or deploy token value
MODULE=io.repsy/hello-world
VERSION=v1.2.0

curl -sf \
  -u "${USER}:${PASS}" \
  -T module.zip \
  -H "Content-Sha256: $(sha256sum module.zip | cut -d' ' -f1)" \
  "${REPSY_URL}/${REPO}/${MODULE}/@v/${VERSION}"
```

Repsy automatically generates the `.info` file on successful upload.

### 3. Verify

```bash
# Should return the version string
curl -sf "${REPSY_URL}/${REPO}/${MODULE}/@v/list"

# Should return JSON: {"Version":"v1.2.0","Time":"..."}
curl -sf "${REPSY_URL}/${REPO}/${MODULE}/@v/${VERSION}.info"
```

---

## Consuming a Module

Only two environment variables are needed. **Do not set `GOPRIVATE` or `GONOPROXY`** — they cause Go to skip the proxy and attempt VCS discovery directly, which is the opposite of what you want.

### Minimal working configuration

```bash
go env -w GOPROXY="https://repsy.example.com/my-go-repo,off"
go env -w GONOSUMDB="*"
```

- `GOPROXY`: use `,off` as the fallback (not `,direct`) so Go fails loudly if the module is not in Repsy
- `GONOSUMDB`: accepts module path prefixes or `*` to disable checksum DB lookups for all modules. Private modules are not in `sum.golang.org`

### Authenticated repository

```bash
go env -w GOPROXY="https://alice:secret@repsy.example.com/my-go-repo,off"
go env -w GONOSUMDB="*"
```

### Deploy token

```bash
go env -w GOPROXY="https://token:dt_abc123@repsy.example.com/my-go-repo,off"
go env -w GONOSUMDB="*"
```

### Fetch a module

```bash
go get io.repsy/hello-world@v1.2.0
```

---

## CI/CD Configuration

### GitHub Actions

```yaml
env:
  GOPROXY: "https://${{ secrets.REPSY_USER }}:${{ secrets.REPSY_TOKEN }}@repsy.example.com/my-go-repo,off"
  GONOSUMDB: "*"

steps:
  - uses: actions/checkout@v4
  - uses: actions/setup-go@v5
    with:
      go-version: '1.22'
  - run: go build ./...
```

### GitLab CI

```yaml
variables:
  GOPROXY: "https://${REPSY_USER}:${REPSY_TOKEN}@repsy.example.com/my-go-repo,off"
  GONOSUMDB: "*"

build:
  image: golang:1.22
  script:
    - go build ./...
```

### Publish script

```bash
#!/usr/bin/env bash
set -euo pipefail

REPSY_URL="${REPSY_URL:?}"
REPO="${REPO:?}"
REPSY_USER="${REPSY_USER:?}"
REPSY_TOKEN="${REPSY_TOKEN:?}"
MODULE="${MODULE:?}"    # e.g. io.repsy/hello-world
VERSION="${VERSION:?}"  # e.g. v1.2.0

# Build zip (files only, no -r)
find "${MODULE}@${VERSION}" -type f | xargs zip module.zip

curl -sf -u "${REPSY_USER}:${REPSY_TOKEN}" \
  -T module.zip \
  -H "Content-Sha256: $(sha256sum module.zip | cut -d' ' -f1)" \
  "${REPSY_URL}/${REPO}/${MODULE}/@v/${VERSION}"

echo "Published ${MODULE}@${VERSION}"
```

---

## Deploy Tokens

Deploy tokens scope access to a single repository and are recommended for CI/CD instead of user credentials. They are managed through the Repsy UI (Repository → Settings → Deploy Tokens).

Use a deploy token in `GOPROXY`:

```bash
go env -w GOPROXY="https://token:dt_abc123@repsy.example.com/my-go-repo,off"
```

---

## Checksum Database

Repsy does not implement a checksum database. This is consistent with Cloudsmith, Athens, and
Google Artifact Registry.

For private modules, set `GONOSUMDB` to skip checksum verification:

```bash
go env -w GONOSUMDB="io.repsy"
```

Public modules are still verified against `sum.golang.org`.

If you want to disable checksum verification entirely (not recommended):

```bash
go env -w GONOSUMDB="*"
```

---

## Known Limitations

| Limitation | Detail |
|---|---|
| No `go mod upload` command | Repsy is a push registry. You build and upload `.mod` + `.zip` yourself. |
| No upstream proxying | Repsy does not proxy to `proxy.golang.org`. Public modules must be fetched via `direct` or a separate proxy entry — but with `,off` as the fallback they will fail. Adjust `GOPROXY` per project as needed. |
| Module paths are lowercased | Repsy normalises all module paths to lowercase on upload and download. Go's uppercase-escape encoding (`!x` → `X`) is decoded first, then the result is lowercased. |
| `.info` timestamp is server-side | The `Time` field in the generated `.info` file is set to the moment the `.zip` was uploaded, not the original commit timestamp. |
| Version immutability enforced | Re-uploading the same version is rejected with `409 Conflict`. Bump the version to publish a new release. |
| Checksum database not updated | Modules are not submitted to `sum.golang.org`. Consumers must set `GONOSUMDB`. |

---

## Troubleshooting

### `go get` still attempts VCS discovery even though `GOPROXY` is set

**Cause:** `GOPRIVATE` or `GONOPROXY` is set. These variables tell Go to bypass the proxy for matching module prefixes and go directly to the VCS — the opposite of what you want.

**Fix:** Unset them and rely only on `GOPROXY` and `GONOSUMDB`:

```bash
go env -w GOPRIVATE=""
go env -w GONOPROXY=""
go env -w GOPROXY="https://repsy.example.com/my-go-repo,off"
go env -w GONOSUMDB="*"
```

---

### `dial tcp: lookup io.repsy: no such host`

The module path domain does not resolve, which is expected. This error means Go is attempting VCS discovery instead of using the proxy. See above — check that `GOPRIVATE` / `GONOPROXY` are not set.

---

### `verifying io.repsy/hello-world@v1.2.0: checksum mismatch` or `not found in sum.golang.org`

Private modules are not indexed in the public checksum database.

```bash
go env -w GONOSUMDB="*"
# or scope to a prefix:
go env -w GONOSUMDB="io.repsy"
```

---

### `go get` returns 404 after a successful upload

Check that the files were generated correctly:

```bash
curl -v "${REPSY_URL}/${REPO}/${MODULE}/@v/${VERSION}.mod"
curl -v "${REPSY_URL}/${REPO}/${MODULE}/@v/${VERSION}.info"
```

If either returns 404 or has 0 bytes, re-upload using `-T` (not `--data-binary` or `-F`).

---

### `zip file corrupt` or `unexpected EOF`

The uploaded zip does not match Go's module zip specification. Inspect the structure:

```bash
unzip -l module.zip | head -20
# All entries must be prefixed with: io.repsy/hello-world@v1.2.0/
# No directory-only entries should appear
```

If directory entries are present, rebuild the zip without `-r`:

```bash
find "${MODULE}@${VERSION}" -type f | xargs zip module.zip
```
