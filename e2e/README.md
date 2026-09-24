# Repsy e2e

An end-to-end protocol-test harness (Playwright + TypeScript), outside the Maven reactor, that
drives real client flows (`mvn deploy`, `npm publish`, ...) against a real Repsy instance, with all
data seeded through the panel API. Step 1 ("skeleton") built the tooling, config, the panel API
client, a seeder with cleanup, a sweep script and the stack/runner containers, proven by one test
suite (`tests/skeleton`). Step 2 ("scenario engine + maven") built the scenario model
(`src/scenarios/`) and its catalog, the maven client adapter and runner, and the full catalog
running green against a local stack; since then the maven catalog also covers SNAPSHOT deploys and
redeploys, and the refusal scenarios check that nothing was stored ("SNAPSHOT and redeploy
behaviour, as probed", below). This is **step 3a ("generalise the engine + npm")**: the scenario
loop itself (`scenarios/loop.ts`) and the `ProtocolAdapter` interface (`scenarios/adapter.ts`) it
runs, generalised out of what was maven-only code so a second protocol reuses it verbatim, plus the
npm client adapter and runner. This is **step 3b ("cargo")**: a third worked example of the same
`ProtocolAdapter` shape, the Cargo (Rust crate registry) client adapter and runner, plus a second
routing-around hook on the loop itself (`ProtocolAdapter.knownPublishSideEffect`, the publish-side
analogue of `knownConsumeFailure`, for a candidate backend bug that corrupts storage on a refused
duplicate publish — see "Cargo runner" below). This is **step 3c ("nuget")**: a fourth worked
example, the NuGet (.NET package registry) client adapter and runner — the first protocol in this
harness with a REAL override conflict (`409`) and a real releases/snapshots `422`, so it is also the
first to exercise the catalog's `conflict` outcome for real (see "NuGet runner" below). This is
**step 4a ("docker")**: a fifth worked example, the Docker (Registry HTTP API V2 / OCI distribution)
client adapter and runner — the first protocol in this harness with a genuine two-hop Bearer
token-exchange auth model instead of Basic-per-request, and the first with a daemonless real client
(`crane`, go-containerregistry) instead of a language toolchain (see "Docker runner" below). This is
**step 4b ("helm")**: a sixth worked example, and the first protocol where Repsy implements TWO
independent wire protocols on the same port for the same package format — OCI distribution-spec
(`helmAdapter`, `clients/helm.ts`) and the classic ChartMuseum protocol (`helmClassicAdapter`,
`clients/helm-classic.ts`) — so the shared catalog runs TWICE, once per mode, inside one runner/
project (see "Helm runner" below). This is **step 4c ("pypi")**: a seventh worked example, the PyPI
(Python Package Index) client adapter and runner — real `twine upload`/`pip download` against a
hand-built wheel, a single-hop Basic auth model like maven/npm/cargo/nuget/helm, and a real,
per-FILENAME override rule (see "PyPI runner" below). This is **step 4d ("golang")**: an eighth
worked example, the Go module proxy (GOPROXY protocol) client adapter and runner — the first
protocol in this harness with NO official publisher at all (Repsy is push-only; the only documented
way in is a single `curl -T`), so `publish`/`seedPublish` drive real `curl` while `resolve` drives
the real `go` toolchain, and the first protocol whose consume side needs its own in-process HTTPS
terminator (`clients/golang-tls-shim.ts`) because the `go` command refuses outright to pass
credentials to a plain-http `GOPROXY` URL (see "Go runner" below). This is **step 4e ("ruby")** —
**the LAST protocol adapter of step 4**: a ninth worked example, the Ruby gem (RubyGems/Bundler)
client adapter and runner, real `gem push`/`bundle install` against a hand-built `.gem`, and the
step whose plan carried the single most consequential gating hypothesis of the whole harness — that a
real `bundle install` could not consume from Repsy OS at all — which was probed live FIRST and
REFUTED (see "Ruby runner" below). Step 4 is now complete: eight worked examples plus this one prove
the `ProtocolAdapter` shape scales across every package format Repsy implements; nothing about the
model itself is maven-specific.

## Rules

- **Every name this harness creates is prefixed `e2e-<runid>-`** (see `src/seed/run-id.ts`), so a
  crashed run's leftovers are always identifiable and `./run.sh sweep` can find them without
  touching anything else.
- **Every test cleans up after itself.** The `seeder` fixture (`src/scenarios/fixtures.ts`) tracks
  everything it creates and deletes it in reverse order when the test ends, tolerating an entity
  that is already gone.
- **Tests only ever run inside a runner container**, never on the host (`docker-compose.runners.yml`).
  The host needs Docker; it does not need Node, Maven, or any protocol client. (Host-side `pnpm
install`/`lint`/`tsc`/`gen:api`/`format` are dev tooling, not test execution, and are fine to run
  directly — see Verification below.)
- **Nothing global is ever touched**: the `admin` user, and a fresh instance's 9 default repos, are
  never created, modified or deleted by anything in this harness.

## Layout

```
e2e/
  package.json  pnpm-lock.yaml  tsconfig.json  eslint.config.js  .prettierrc  .env.example
  playwright.config.ts        # one project per protocol: "skeleton", "maven", "npm", "cargo", "nuget", "docker", "helm", "pypi", "golang", "ruby"; plus "ui" (the panel in headless Chromium, see "UI suite")
  run.sh                       # single entry point: local | test | sweep
  docker-compose.stack.yml     # postgres profile: postgres:18 + Repsy, `run.sh local up|down`
  docker-compose.stack-h2.yml  # H2 profile: Repsy alone (embedded H2, no postgres service), `run.sh local up|down --h2`
  docker-compose.runners.yml   # one runner service per protocol: "skeleton", "maven", "npm", "cargo", "nuget", "docker", "helm", "pypi", "golang", "ruby"; plus "ui"
  runners/base.Dockerfile      # node:24 + pinned pnpm + the harness; the "skeleton" runner
  runners/maven.Dockerfile     # + pinned Temurin/Maven; see "Adding a protocol adapter" below
  runners/npm.Dockerfile       # + nothing else: npm ships with the node:24 base already
  runners/cargo.Dockerfile     # + a pinned Rust toolchain, copied in from the official rust image
  runners/nuget.Dockerfile     # + a pinned .NET SDK, copied in from the official Ubuntu-noble SDK image
  runners/docker.Dockerfile    # + the static `crane` binary copied out of its own distroless image; no daemon, no socket
  runners/helm.Dockerfile      # + the static `helm` binary + the cm-push plugin installed at build time; no daemon, no socket
  runners/pypi.Dockerfile      # + a pinned CPython copied out of the official python image; pip/twine installed at build time
  runners/golang.Dockerfile    # + a pinned Go toolchain copied out of the official golang image, `curl`, and a build-time TLS cert/key for the shim
  runners/ruby.Dockerfile      # + a pinned Ruby toolchain (ruby/gem/bundle/bundler + stdlib) copied out of the official ruby image
  runners/ui.Dockerfile        # + Playwright's own headless Chromium (build-time install, /ms-playwright); the "ui" runner, see "UI suite"
  runners/ui-seccomp.json      # Playwright's seccomp profile, so Chromium's sandbox works as a non-root uid in Docker
  runners/entrypoint.sh         # regenerates the API client, then runs Playwright for one project
  src/
    env.ts                     # typed config from env/.env
    target.ts                  # capabilities derived from REPSY_TARGET
    api/
      panel-api.ts             # hand-written wrapper around the generated client
      generated/                # `pnpm gen:api` output, git-ignored
    seed/
      run-id.ts                 # e2e-<runid>- naming, length/pattern limits
      seeder.ts                 # createUser/createRepo/setSettings/createToken + cleanup(); reserve*/adopt* for entities the UI creates
      sweep.ts                  # deletes e2e-* leftovers older than N hours (or --all)
    scenarios/
      types.ts                  # Scenario/Outcome model, outcomeForStatus(), expectationFor()
      catalog.ts                # the scenario matrix -- see "Scenario model" below
      adapter.ts                 # ProtocolAdapter/AdapterResult -- what the loop needs from a client
      loop.ts                    # registerPublishConsumeLoop(adapter): the scenario loop, protocol-agnostic
      coordinates.ts             # slugify(), boundedSemverVersion() -- shared by every adapter's packageName()/version()
      world.ts                  # World/Coordinates/MaterializedCredential types
      fixtures.ts                # Playwright fixtures: panelApi, seeder, world(scenario, adapter)
      remote-throttle.ts        # RemoteAuthBudget/withBackoff429 -- see "Remote hardening" below
    ui/                        # the panel UI suite's plumbing (fixtures, session seeding, page objects) -- see "UI suite"
    clients/
      exec.ts                   # execa wrapper: isolated work dir/HOME, redacted logs, attach-on-fail
      raw-http.ts                # shared raw-HTTP building blocks: RawResponse, adminCredential(), authHeader(), sha256Hex, 429 backoff
      maven.ts                  # the maven client: publish()/resolve()/seedPublish(), raw-HTTP status pinning
      maven-raw.ts              # maven-specific raw PUT/GET, repo-tree fingerprint, maven-metadata.xml builders/parsers
      maven-checks.ts            # expectNothingStored / expectSnapshotFollowedThroughMetadata
      maven-adapter.ts           # mavenAdapter: the ProtocolAdapter object registerPublishConsumeLoop takes
      npm-raw.ts                  # npm-specific raw PUT/GET (packument/tarball), publish-document builder
      npm.ts                      # the npm client + npmAdapter: publish()/resolve()/seedPublish(), npm pack/publish/install
      pgp.ts                     # real OpenPGP.js key generation and detached signing, no gpg/network
      cargo-raw.ts                 # cargo-specific raw PUT/GET (publish/config.json/sparse-index/download), body builder
      cargo.ts                     # the cargo client + cargoAdapter: publish()/resolve()/seedPublish(), cargo package/publish/fetch
      nuget-raw.ts                  # nuget-specific raw PUT/GET (publish/versions/download/registration/service-index), buildNupkg (fflate)
      nuget.ts                      # the nuget client + nugetAdapter: publish()/resolve()/seedPublish(), dotnet nuget push/restore
      docker-image.ts                # hand-assembled OCI image layout builder (layer tar+gzip, config, manifest, index.json, oci-layout)
      docker-raw.ts                  # docker-specific raw HTTP: the two-hop token dance, manifest/blob PUT/GET/HEAD, OCI error envelope
      docker.ts                      # the docker client + dockerAdapter: publish()/resolve()/seedPublish(), crane push/pull
      helm-chart.ts                   # hand-assembled Helm chart .tgz builder (Chart.yaml + values.yaml + marker, ustar+gzip)
      helm-raw.ts                     # raw HTTP for BOTH Helm protocols: OCI manifest/blob PUT/GET/HEAD + classic index/chart/upload/delete
      helm.ts                         # the OCI client + helmAdapter: publish()/resolve()/seedPublish(), helm push/pull --plain-http
      helm-classic.ts                  # the classic (ChartMuseum) client + helmClassicAdapter: helm cm-push / pull --repo
      pypi-raw.ts                     # pypi-specific raw POST/GET (upload/simple page/root index/download), buildWheel (fflate)
      pypi.ts                          # the pypi client + pypiAdapter: publish()/resolve()/seedPublish(), python3 -m twine/pip
      golang-raw.ts                     # golang-specific raw PUT/GET (@v/list, @latest, .info/.mod/.zip), buildModuleZip (fflate), dirhashHash1
      golang-tls-shim.ts                 # in-process HTTPS reverse proxy for a credentialed consume (a real `go` refuses plain-http creds)
      golang.ts                          # the golang client + golangAdapter: publish()/resolve()/seedPublish(), real curl -T / go mod download
      ruby-raw.ts                          # ruby-specific raw POST/GET/DELETE (gems/yank/versions/info/names/specs.4.8.gz), buildGem (buildTar + node:zlib)
      ruby.ts                              # the ruby client + rubyAdapter: publish()/resolve()/seedPublish(), real gem push / bundle install
    packages/
      maven/                     # mustache templates of the tiny jar project + settings.xml
      npm/                       # mustache templates of the tiny package.json/index.js + .npmrc
      cargo/                     # mustache templates of the tiny crate + consumer Cargo.toml + .cargo/config.toml
      nuget/                     # mustache templates of nuget.config + the consumer .csproj (the .nupkg itself is built in code, see nuget-raw.ts)
      docker/                    # config.template.json (DOCKER_CONFIG auths entry; the image itself is built in code, see docker-image.ts)
      helm/                      # Chart.template.yaml + registry-config.template.json (HELM_REGISTRY_CONFIG auths entry)
      # no packages/pypi/: the wheel is built entirely in code (line-based text), see pypi-raw.ts's buildWheel
      golang/                    # go.template.mod + hello.template.go (rendered into the zip in code) + consumer-go.template.mod/consumer-main.template.go
      ruby/                      # metadata.template.yaml (the hand-built .gem's gzipped gemspec YAML) + lib.template.rb + Gemfile.template
  tests/
    ui/                         # the panel UI suite (Playwright + headless Chromium): smoke.spec.ts (@smoke) and harness.spec.ts, one folder per area from here on -- see "UI suite"
    skeleton/seed.spec.ts       # proves seeding, cleanup and a real auth probe; both tests tagged @smoke
    skeleton/repo-settings.spec.ts  # RPS-1200 settings-PUT field-by-field matrix across RepoTypes; untagged (not smoke-sized)
    maven/
      publish-consume.spec.ts   # registerPublishConsumeLoop(mavenAdapter) + the RPS-1196 real-client test
      upload-rules.spec.ts      # raw-HTTP pins of the override / releases / snapshots upload rules
      pgp-signature.spec.ts     # registered PGP public keys (RPS-1189): verify, reject, isolate, delete
      remote-throttle.spec.ts   # sanity check of RemoteAuthBudget/withBackoff429, no server needed
    npm/
      publish-consume.spec.ts   # registerPublishConsumeLoop(npmAdapter) + a scoped-package real-client test
      registry-rules.spec.ts    # raw-HTTP pins of override/version-validation rules + the RPS-1205 tarball probe
      unpublish.spec.ts         # real `npm unpublish` (RPS-1289): one version (unscoped/scoped), the only version, a whole package
    cargo/
      publish-consume.spec.ts   # registerPublishConsumeLoop(cargoAdapter) + a hyphenated-crate-name real-client test
      registry-rules.spec.ts    # raw-HTTP pins of the duplicate-version/version-validation/config.json/name-normalisation rules
    nuget/
      publish-consume.spec.ts   # registerPublishConsumeLoop(nugetAdapter) + api-key-only-push and mixed-case-id real-client tests
      registry-rules.spec.ts    # raw-HTTP pins of the 409/422 override & version-kind rules, service index, X-NuGet-ApiKey (H7)
    docker/
      publish-consume.spec.ts   # registerPublishConsumeLoop(dockerAdapter) + D1-D4 real-client tests (OCI family, auth login, by-digest, retag)
      registry-rules.spec.ts    # raw-HTTP pins R1-R13: token dance, blob/manifest rules, override, HEAD-vs-GET, retag, bad config/content-type
    helm/
      publish-consume.spec.ts          # registerPublishConsumeLoop(helmAdapter) + HL1/HL2/HL4/HL5 real-client tests (OCI mode)
      classic-publish-consume.spec.ts  # registerPublishConsumeLoop(helmClassicAdapter) + C1-C3 real-client tests (classic/ChartMuseum mode)
      registry-rules.spec.ts           # raw-HTTP pins R1-R14 for BOTH modes: single-hop Basic auth, blob/manifest rules, override, tags/list, classic upload/delete/index shape
    pypi/
      publish-consume.spec.ts   # registerPublishConsumeLoop(pypiAdapter) + a real pip-install and a mixed-case/dotted-name real-client test
      registry-rules.spec.ts    # raw-HTTP pins of the override/version/digest rules, root-index shape, HEAD, 307 redirect, no releases/snapshots rule
    golang/
      publish-consume.spec.ts   # registerPublishConsumeLoop(golangAdapter) + go-get-build-run, plain-http-creds-refused, dirhash cross-check, mixed-case and wire-trace real-client tests
      registry-rules.spec.ts    # raw-HTTP pins R1-R16 (auth, upload URL spellings, sha256, immutability, zip validation, @v/list/@latest, sumdb, HEAD, delete+reupload) + G1/G2/G10 candidates
    ruby/
      publish-consume.spec.ts   # registerPublishConsumeLoop(rubyAdapter) + gem-install (RPS-1233, fixed)/gem-fetch (RPS-1234, fixed), anonymous-push, yank (RPS-1235, fixed), USER-role-push, bundle-install-e2e real-client tests
      registry-rules.spec.ts    # raw-HTTP pins R1-R16 (auth, override row-first, malformed gem, full yank flow incl. RPS-1238 fixed, specs.4.8.gz gzip framing (RPS-1234, fixed), gemspec.rz (RPS-1233, fixed), HEAD mirrors GET (RPS-1237, fixed), platform gem, RPS-1236 fixed) -- no remaining test.fail() pins
```

## Setup

```bash
cd e2e
cp .env.example .env   # fill in REPSY_ADMIN_PASSWORD
pnpm install
pnpm gen:api            # generates src/api/generated from ../repsy-backend's openapi spec
```

## Environment

| Variable                      | Default                    | Notes                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| ----------------------------- | -------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `REPSY_API_BASE_URL`          | `http://localhost:8080`    | panel API                                                                                                                                                                                                                                                                                                                                                                                                                                |
| `REPSY_REPO_BASE_URL`         | `http://localhost:9090`    | repository/protocol operations                                                                                                                                                                                                                                                                                                                                                                                                           |
| `REPSY_ADMIN_USERNAME`        | `admin`                    |                                                                                                                                                                                                                                                                                                                                                                                                                                          |
| `REPSY_ADMIN_PASSWORD`        | _(none — required)_        | must match the target's admin password                                                                                                                                                                                                                                                                                                                                                                                                   |
| `REPSY_TARGET`                | `local`                    | `local` \| `remote` \| `ci` — see Targets below                                                                                                                                                                                                                                                                                                                                                                                          |
| `REPSY_E2E_RUN_ID`            | random 6-char lowercase id | shared by every runner in one `run.sh test`                                                                                                                                                                                                                                                                                                                                                                                              |
| `REPSY_E2E_STACK`             | _(unset — postgres)_       | `local up\|down` stack profile: unset/anything but `h2` is the postgres profile, `h2` is the embedded-H2 profile; equivalent to `--h2` on the command line. Unread by `run.sh test`, which is identical against either profile — see "Stack profiles" below                                                                                                                                                                              |
| `REPSY_UI_BASE_URL`           | _(REPSY_API_BASE_URL)_     | ui runner only: where the panel SPA is (it is served on the API port 8080, not the protocol port 9090)                                                                                                                                                                                                                                                                                                                                   |
| `REPSY_UI_WORKERS`            | `4` (compose)              | ui runner only: Playwright workers (each is a Chromium, ~250-400 MB)                                                                                                                                                                                                                                                                                                                                                                     |
| `REPSY_UI_NO_SANDBOX`         | _(unset — sandbox on)_     | ui runner only: `1` launches Chromium with `chromiumSandbox: false`, see "UI suite"                                                                                                                                                                                                                                                                                                                                                      |
| `REPSY_UI_OPT_IN`             | _(unset)_                  | ui runner only: comma list of opt-in UI suites (`throttle`, `scanner`); read by `optedIn()`                                                                                                                                                                                                                                                                                                                                              |
| `REPSY_E2E_INSECURE_REGISTRY` | _(unset)_                  | docker runner's `--insecure` (only needed for a remote plain-HTTP host; `localhost` already works without it); helm runner's `--insecure-skip-tls-verify` (a REMOTE HTTPS target with a bad cert only -- helm's own `--plain-http` is derived from `REPSY_REPO_BASE_URL`'s scheme instead, unconditionally on this harness's own `http://localhost:9090` stack, confirmed live H3: unlike `crane`, Helm has no localhost auto-detection) |

## Targets (`src/target.ts`)

- **local** — a stack this harness starts and owns (`./run.sh local up`); throttle limits can be
  raised freely for negative-auth scenarios.
- **ci** — a pipeline-started stack; same freedoms as `local`. Wiring is deferred (see the plan).
- **remote** — an already-running instance the harness does not own or reset. Throttle cannot be
  tuned and nothing global is touched; later steps add a failure budget and a preflight check.

## Stack profiles (postgres and H2)

`./run.sh local up|down` starts/stops one of two mutually exclusive stack profiles, chosen with
`--h2` (or `REPSY_E2E_STACK=h2`):

- **postgres** (default, `docker-compose.stack.yml`) — `postgres:18` + Repsy.
- **H2** (`docker-compose.stack-h2.yml`) — Repsy alone, backed by the embedded H2 database. No
  `postgres` service at all: a compose _override_ cannot remove a service, and profile-gating
  `postgres` while `repsy` still `depends_on` it makes Compose auto-enable the disabled service
  anyway, so this is a second, standalone compose file instead. Both files share the same
  `name: repsy-e2e` project and the same `8080`/`9090` ports, so the two profiles can never run at
  once by construction, and `./run.sh local down` (either flavour) always tears down whichever one
  is actually up.

`./run.sh test` needs **no flag and no code change at all**: a runner only ever sees
`REPSY_API_BASE_URL`/`REPSY_REPO_BASE_URL` (both `localhost`, identical in either profile), so every
spec, adapter and runner image is bit-for-bit unchanged between the two databases. This is
deliberate — see "Scope decision" below.

**Fresh database per `up`**: neither compose file mounts a named volume for `/app/data`. The image
declares `VOLUME /app/data`, so each `repsy` container gets its own anonymous volume; `down` followed
by `up` therefore always starts from an empty database with the 9 default repos freshly seeded,
mirroring the postgres profile's own anonymous `pgdata` volume (confirmed live — see "Verification").

### H2-1, confirmed live: which `DB_URL` actually boots the image

`repsy-backend/src/main/resources/application.yml` defaults `spring.datasource.url` to
`jdbc:postgresql://localhost:5432/repsy`, **not** H2 — nothing in the root `Dockerfile`/
`entrypoint.sh` overrides it, so H2 is only ever selected by setting `DB_URL` explicitly. Two
variants were run directly against the locally built image (`repsy-os-e2e:local`, from
`./run.sh local up`/`down` against the postgres profile) with `docker run` (no compose, no
postgres container reachable):

- **(a)** the root `README.md`'s own documented default value,
  `DB_URL=jdbc:h2:file:/app/data/repsy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE` —
  **boots cleanly.** Flyway ran all 19 H2 migrations with no error, the app logged
  `repsy started successfully!`, the SPA answered `200` on `/`, `POST /api/auth/login` with the
  configured `ADMIN_INITIAL_PASSWORD` returned a token, and `GET /api/repos/{TYPE}/info` for all 9
  `RepoType`s (`MAVEN`, `NPM`, `PYPI`, `DOCKER`, `CARGO`, `GOLANG`, `HELM`, `NUGET`, `RUBY`) each
  returned exactly the one expected default repo.
- **(b)** the same value with `;DATABASE_TO_LOWER=TRUE` appended (matching
  `H2IntegrationTest`'s own in-memory URL) — **also boots cleanly**, same Flyway/login/repos-list
  result. This harness pins **(a)**, the exact value already documented in the root `README.md`'s
  environment table, since it already works and needs no extra flag; `DATABASE_TO_LOWER=TRUE` turned
  out not to be load-bearing for this schema/H2-version combination, contrary to the plan's initial
  suspicion — confirmed live, not assumed.

There was no losing variant to record an error for: both DB_URL values booted the image cleanly.

### H2-2, confirmed live: no `DB_URL` at all does NOT default to H2

The root `README.md`'s "Quick Start" and "Option 1: Docker with H2 (Embedded Database)" sections
both imply (and the former's `docker run` example sets no `DB_URL` at all) that omitting `DB_URL`
gives you embedded H2. Run directly against the locally built image with no `DB_URL` set, the
container instead tries the `application.yml` default and fails immediately:

```
Caused by: org.postgresql.util.PSQLException: Connection to localhost:5432 refused. Check that the
hostname and port are correct and that the postmaster is accepting TCP/IP connections.
```

(Full chain: `FlywayAutoConfiguration` fails to resolve migration locations because it cannot open a
JDBC connection at all — this happens before the app can even seed default repos.) This is exactly
the documentation bug already tracked by
[RPS-1173](https://zyfera.atlassian.net/browse/RPS-1173) ("README says the embedded H2 database is
the default, but application.yml defaults DB_URL to PostgreSQL") — re-confirmed live here with fresh
evidence (commented on that ticket) rather than filed again. **Not fixed here**: this step touches
only `e2e/`, never the backend or its docs. **Fixed by RPS-1173 itself**: the `Dockerfile` now sets
`ENV DB_URL=jdbc:h2:file:/app/data/repsy;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`
(the exact H2-1 (a) value this section already pinned), so the image now boots with embedded H2 when
no `DB_URL` is passed, matching the README. This transcript is kept as the historical record of the
bug, not rewritten.

### Scope decision: `@smoke` everywhere plus one full catalog, not ten full catalogs

RPS-294's own "Out of scope" text keeps a per-database full run out of scope: "The design keeps it a
matter of `run.sh local up && run.sh test` per protocol in a matrix, and an `@smoke` tag already
selects a PR-sized subset." The "passes the full scenario catalog for every protocol" acceptance
criterion is about the default (postgres) stack, not about repeating that catalog per stack profile.
Each protocol suite runs roughly 29-45 tests; a full second pass across all 9 protocols would
roughly double this harness's wall-clock time for a question that is database-agnostic above the JPA
layer. Schema/JPA/dialect divergence between postgres and H2 is already covered on the backend side
by `H2IntegrationTest` and the `H2*IT` suite; what only the e2e H2 profile can show is that the
built image actually boots on H2 and that each protocol's real client completes a round trip against
it — which `@smoke` across every runner, plus one full catalog (maven, the protocol with the most
repo-setting scenarios) on H2, demonstrates without doubling the run.

### H2-9, confirmed live: the skeleton `@smoke` tag gap

Before this step, no test under `tests/skeleton/` carried an `@smoke` tag, so
`./run.sh test --protocol skeleton --grep '@smoke'` reported "No tests found" and a non-zero exit —
confirmed live. Both tests in `tests/skeleton/seed.spec.ts` (the seed/cleanup test and the
expired-token test) now carry `{ tag: ['@smoke'] }`, the same tag signature every other suite's
dedicated tests already use. `tests/skeleton/repo-settings.spec.ts` (RPS-1200's settings-PUT
field-by-field matrix, looped over `RepoType.NPM`/`RepoType.MAVEN`) is left untagged: it is the
settings-matrix-style test the plan says to leave alone, not smoke-sized.

## Scenario model

A scenario (`src/scenarios/types.ts`) is data, not a test file: an id, tags, the repo settings it
needs, which credential it uses, and the `Outcome` (`'ok' | 'unauthorized' | 'forbidden' |
'conflict' | 'rejected'`) expected for a publish and a consume attempt. `src/scenarios/catalog.ts`
holds the one shared catalog every protocol draws from; `scenariosFor(catalog, protocol)` filters it
to the scenarios that protocol applies to (a scenario can restrict itself to specific protocols via
its `protocols` field — see `maven-releases-off`/`maven-snapshots-off`, maven-only).

`fixtures.ts`'s `world(scenario, protocol)` fixture turns a scenario into a ready-to-test `World`
(`src/scenarios/world.ts`): a fresh repo with the scenario's settings applied, the credential
materialised through the panel API (expired = past `expiration_date`, revoked = create then revoke,
rotated-old = create, rotate, and deliberately keep the _old_ value, other-repo = a token from a
second seeded repo, user-password = a seeded `USER`-role user, wrong-password = a real username with
a wrong password, anonymous = no credential at all), and, for a scenario whose own credential cannot
publish but is still expected to consume successfully, a package pre-published with the admin
credential first. Everything created is tracked by the `Seeder` and removed in test teardown.

A spec is then a loop over `scenariosFor(SCENARIOS, protocol)` (see
`tests/maven/publish-consume.spec.ts`): for each scenario, seed the `World`, publish with it,
assert the expected `Outcome`, then consume with it and assert that `Outcome` too. On top of the
outcome, every maven scenario checks what a status cannot show: the real `mvn` exit code agrees with
the outcome (a refused deploy fails the client, an accepted one does not); a refused publish left the
repository byte-for-byte as it was (see "Nothing stored" below); and a consumer that succeeded got the
very jar that was deployed (the deploy's own jar, or the pre-published one when the scenario's own
publish was refused). Every deploy packs a fresh random marker into its jar, so two deploys of one
coordinate never share a digest.

### Two coordinates, or one

`World` carries `publishTarget` and `consumeTarget` (both `{ packageName, version }`), not a single
package. For most scenarios they are the same coordinate. A scenario whose own publish is expected to
fail while consume still succeeds pre-publishes (with the admin credential, before the scenario's
`repo` settings are applied) a _separate_ coordinate of the same package, so the scenario's own
(doomed) attempt is a genuine first deploy of a version that does not exist yet: that is what
`maven-releases-off`/`maven-snapshots-off` are about, and it gives their "nothing stored" check a
version that must not exist anywhere afterwards.

A scenario with `reuseCoordinates: true` is about a **redeploy** instead: the pre-publish puts the
very coordinate the scenario's own publish then targets, and `consumeTarget === publishTarget`. These
are `no-override`/`override` (a second write to existing coordinates is exactly what `allowOverride`
gates), the RPS-1174 scenarios `redeploy-snapshots-off`/`redeploy-releases-off`, and
`snapshot-redeploy`/`snapshot-redeploy-no-override`. A scenario's `repo` settings are always applied
_after_ its pre-publish, which runs on a fresh repo's permissive defaults, so "deploy while the kind
was still allowed, switch it off, deploy again" needs no extra field.

Before RPS-1174, Repsy's release/snapshot check skipped a redeploy of an existing version entirely
(only the allow-override check applied), so a scenario had to keep a separate coordinate or its
"publish a prohibited version kind" attempt would silently turn into a redeploy that succeeded. Since
RPS-1174 the `releases`/`snapshots` switches judge a redeploy exactly like a first deploy, and the
`redeploy-*-off` scenarios pin that. RPS-1176 makes the version-level snapshot `maven-metadata.xml`
judged by its own `<version>`, exactly like the artifact files of that directory.

### Scenario outcomes pinned against a running instance

Every `expect` in `catalog.ts` was pinned by probing `./run.sh local up` with `curl`, not assumed
from the plan's table (see that file's header comment for the full detail and reasoning):

| scenario                            | publish (real)           | consume (real)           | plan guessed instead                    |
| ----------------------------------- | ------------------------ | ------------------------ | --------------------------------------- |
| token-ro                            | `401 unauthorized`       | `200 ok`                 | publish: forbidden (403)                |
| no-override (2nd publish)           | `403 forbidden`          | `200 ok`                 | publish: conflict (409)                 |
| maven-releases-off (RELEASE push)   | `403 forbidden`          | `200 ok`                 | publish: "rejected", unspecified status |
| maven-snapshots-off (SNAPSHOT push) | `403 forbidden`          | `200 ok`                 | publish: "rejected", unspecified status |
| snapshot-deploy                     | `200 ok`                 | `200 ok`                 | — (not in the plan)                     |
| snapshot-redeploy                   | `200 ok`                 | `200 ok`                 | — (not in the plan)                     |
| snapshot-redeploy-no-override       | `200 ok`                 | `200 ok`                 | — (a refusal was the fear, see below)   |
| redeploy-snapshots-off (RPS-1174)   | `403 forbidden`          | `200 ok`                 | — (not in the plan)                     |
| redeploy-releases-off (RPS-1174)    | `403 forbidden`          | `200 ok`                 | — (not in the plan)                     |
| everything else                     | matches the plan's table | matches the plan's table | —                                       |

In short: `ProtocolAuthService.authorizeDeployToken` (a read-only token attempting a write) throws
the same plain `UnAuthorizedException` it throws for "no credentials at all", so `token-ro`'s publish
gets a 401, not a distinct "forbidden" status — Repsy has no separate "authenticated but
insufficient permission" status for a deploy token. Rejecting an override, a release version or a
snapshot version throws `AccessNotAllowedException`, which `ErrorHandler` maps to 403 — not the
plan's 409/"conflict".

### SNAPSHOT and redeploy behaviour, as probed

A real `mvn deploy` PUTs all artifact files first (the POM, the jar, each followed by its
checksums), then all metadata, and stops at the first refusal. A snapshot deploy uploads timestamped
files (`lib-1.0-20260921.101010-1.jar`), the version-level `g/a/1.0-SNAPSHOT/maven-metadata.xml`
(`<version>`, `versioning/snapshot` timestamp and buildNumber, `snapshotVersions`) and the
artifact-level `g/a/maven-metadata.xml` (`<versions>`). Every redeploy writes new timestamped files
with buildNumber + 1 and uploads both metadata files again. The consumer (`dependency:get` of
`X-SNAPSHOT` into a clean local repo) resolves through the version-level metadata to the timestamped
jar; `snapshot-deploy`/`snapshot-redeploy` assert the metadata's buildNumber, the resolved
timestamped file name, that every deploy's own jar is still stored, and that the resolved jar is the
deployed one.

What the server does, per rule (all pinned above or in `tests/maven/upload-rules.spec.ts`):

- **A switched-off kind** (`releases: false` / `snapshots: false`) refuses an upload of that kind
  with `403` (`releaseVersionsAreProhibited` / `snapshotVersionsAreProhibited`) on the first file of
  the deploy, whether the version is new or already exists (RPS-1174). The other kind keeps working.
- **Metadata**: the version-level snapshot `maven-metadata.xml` is judged by its `<version>` (RPS-1176:
  refused under `snapshots: false`, accepted under `releases: false`); the artifact-level file lists
  both kinds and is never judged. A checksum (`.sha1`/`.md5`/`.sha256`/`.sha512`) is judged by the
  file it belongs to (RPS-1183): the layout check (`400 invalidArtifactPath`), the kind switches and
  the override rule apply to it as to that file, so it no longer creates the directory of a version
  whose file is refused. A metadata checksum holds a hash, not XML, so it is judged by its
  directory: `g/a/<X-SNAPSHOT>/maven-metadata.xml.sha1` is a snapshot; the artifact-level and
  group-level ones are not judged. The same holds for the `.asc` signature of a metadata file
  (`maven-metadata.xml.asc`, which no official client writes but Maven Central serves): it is
  stored unparsed, judged only by its directory and not verified (RPS-1185); it used to answer
  `400 malformedMetadataFile`.
- **`allowOverride: false`** refuses re-uploading a file that already exists
  (`403 artifactOverrideIsProhibited`) and never judges metadata. A normal SNAPSHOT redeploy writes
  new timestamped files and re-uploads the metadata, so it **succeeds** under `allowOverride: false`
  (`snapshot-redeploy-no-override`: real client exit 0, consumer resolves buildNumber 2). Only a name
  that already exists is an override, and a real client never sends one twice.
- **A snapshot-directory file named for another artifact or version** (`lib-2.0-SNAPSHOT.jar`,
  `other-1.0-SNAPSHOT.jar`, `lib-1.0-SNAPSHOTX.jar` in `g/lib/1.0-SNAPSHOT/`) is refused with
  `400 invalidArtifactPath` and stores nothing: the file name must start with the directory's
  artifactId and base version, literal or timestamped (RPS-1184). The GAV parser only checks where
  the `SNAPSHOT` marker sits, so the server compares the rest itself.
- **A POM signature** (`<pom>.asc`) is verified against the stored POM **before** it is stored
  (RPS-1186), so a refusal answers `422 artifactSignatureNotVerified` and changes nothing: the
  `.asc` is not stored, and no file, row, other version or `maven-metadata.xml` of the repo is
  touched (the spec compares the whole repo tree before and after, for a release and for a
  timestamped snapshot that has another version beside it). It used to be stored first and the
  version, the artifact and the group deleted on a refusal (a 500 for a timestamped snapshot with
  other versions). A `.pom.asc` that arrives before its `.pom` answers `404 itemNotFound` and stores
  nothing. Only a `.pom.asc` is verified; a `.jar.asc` is stored as sent. The pin sends an `.asc`
  with no signature packet, which is refused before any key server is asked, so no network and no
  `gpg` are needed; a signature that verifies (or fails against a real key) is covered by
  `MavenPomSignatureIT` on the backend side.
  An `.asc` that is not a signature at all (the two armor lines only, a bad CRC, binary garbage)
  answers the same `422 artifactSignatureNotVerified` and stores nothing, where it used to be a
  `500 errorOccurred` (RPS-1191). A `.pom.asc` of a POM that is stored but has no registered version
  answers `404 artifactVersionNotFound` before anything is stored (RPS-1191); such a POM can no
  longer be uploaded (next point), so that case is pinned by `MavenPomSignatureIT` on the backend
  side, not here.
- **A POM whose `<groupId>` is not its directory's** (`<groupId>org.other</groupId>` at
  `g/a/1.0/a-1.0.pom`, or no `<groupId>` and `<parent><groupId>org.other</groupId>`, or an
  expression such as `${g}`, or the group in another case) is refused with `400 pomGroupIdMismatch`
  and stores nothing (RPS-1193). It used to be stored and answered `200` but never registered: the
  file was served, yet invisible and undeletable in the panel, with no artifact row, version event or
  scan. Only the groupId is compared, case-sensitively like the layout; a POM that declares none
  (and has no parent) is not checked, and the artifactId and version are never compared (a
  `${revision}` version or an sbt cross-versioned artifactId is legitimate). `mvn deploy` derives the
  path from the POM, so it never produces a mismatch.
- A raw PUT must send an explicit `Content-Type`, or the body is consumed as form data and the
  server answers 400 (see the comment in `clients/maven.ts`).

The adapter's raw publish probe (what pins the exact status) is a PUT of the deploy's first file: the
release POM for a RELEASE, a fresh timestamped POM (`a-<base>-<now>-9000nn.pom`, a build number no real
deploy reaches) for a SNAPSHOT. It is not the literal `a-<base>-SNAPSHOT.pom`: a real client never
sends that name, and it is judged differently (with `allowOverride: false` it is refused as soon as
the version exists, because that rule looks the version up in the database for a POM).

### Nothing stored

After every refused publish the spec compares a fingerprint of the whole repository (every file
found by walking the directory listings from the root, hashed by content, read with the admin
credential whatever the scenario's own credential is) with the one taken right before the publish:
they must be equal, so nothing was stored and nothing was overwritten, metadata included. For a
version that did not exist before, it also asserts `404` on the version directory, its
`maven-metadata.xml`, its POM and jar, and that the artifact-level `maven-metadata.xml` is absent or
does not list the version. The directory listing itself answers `404` for a path with nothing under
it, which is what makes "not stored" a plain status check.

### Adding a scenario

Add an entry to `SCENARIOS` in `catalog.ts` with a unique `id`, the repo settings and credential it
needs, and the pinned `Outcome`s — pin them by probing a running instance the way the header comment
of that file describes, don't guess. Restrict it to specific protocols via `protocols` if it only
makes sense for some (like the maven-only settings scenarios), and set `reuseCoordinates` when the
scenario is about redeploying a coordinate that already exists. It is picked up automatically by every
protocol spec that calls `scenariosFor(SCENARIOS, protocol)`.

### Adding a protocol adapter

The scenario loop itself (`scenarios/loop.ts`'s `registerPublishConsumeLoop`) is protocol-agnostic
as of step 3a (RPS-294): a new protocol implements the `ProtocolAdapter` interface
(`scenarios/adapter.ts`) and hands the loop that one object, instead of copying the loop's
machinery. `clients/maven-adapter.ts`, `clients/npm.ts`'s `npmAdapter`, `clients/cargo.ts`'s
`cargoAdapter`, `clients/nuget.ts`'s `nugetAdapter` and `clients/docker.ts`'s `dockerAdapter` are
five worked examples.

1. `src/clients/<protocol>.ts` (+ a `<protocol>-raw.ts` for its raw-HTTP building blocks, built on
   the shared pieces in `clients/raw-http.ts`): `publish(world)`/`resolve(world)`/`seedPublish(world)`
   (or that protocol's equivalent verbs), each returning an `AdapterResult`
   (`{ outcome, httpStatus, clientExitCode, command, contentSha256?, resolvedFile? }`, `adapter.ts`),
   derived from a **raw HTTP request with the same credential**, not from the client's exit code
   (see `clients/maven.ts`'s file header: a real client hides the HTTP status behind its own exit
   code).
2. `src/clients/<protocol>-adapter.ts` (or inline in `<protocol>.ts`, as `npm.ts` does): the
   `ProtocolAdapter` object itself -- `protocol`, `client` (name/publishVerb/consumeVerb, used only
   in messages), `packageName`/`version` (coordinate generation), `publish`/`resolve`/`seedPublish`
   (from step 1), `fingerprint`/`expectNothingStored` (a snapshot of "what exists" a refused publish
   must leave untouched, and the assertion that it did), and optionally
   `afterSuccessfulRoundTrip`/`knownConsumeFailure` (a known, already-filed backend bug that only
   affects the consume side -- see `npmAdapter.knownConsumeFailure` and RPS-1205 below).
3. `src/packages/<protocol>/`: mustache templates of a tiny publishable project.
4. `runners/<protocol>.Dockerfile`: the toolchain that protocol's client needs, pinned versions as
   build args. See `runners/maven.Dockerfile`'s header comment for why it repeats
   `runners/base.Dockerfile`'s early layers instead of `FROM`ing it as a separately built image.
5. A service in `docker-compose.runners.yml` (copy the `maven` service: same `x-runner-common`/
   `x-runner-environment` anchors, its own named volume for third-party downloads if the client
   caches those -- npm's does not, since its test packages declare no dependencies).
6. A project in `playwright.config.ts` (`testMatch: '<protocol>/**/*.spec.ts'`).
7. `tests/<protocol>/publish-consume.spec.ts`: `registerPublishConsumeLoop(<protocol>Adapter);` (see
   `tests/npm/publish-consume.spec.ts`), plus any real-client test the catalog-driven loop cannot
   express (a scoped-package round trip, for npm).
8. Restrict any scenario your adapter cannot express (or add one only it needs) via the catalog
   entry's `protocols` field, or override its `expect` for your protocol via `expectByProtocol`
   (`scenarios/types.ts`'s `expectationFor`) when the real, probed status differs from maven's.

## Maven runner

`runners/maven.Dockerfile` adds a pinned Eclipse Temurin JDK and Apache Maven (build args
`TEMURIN_VERSION`, `MAVEN_VERSION`) to the harness image. `clients/maven.ts` renders
`src/packages/maven/{pom,settings}.template.xml` into a per-invocation isolated work directory
(`clients/exec.ts`) and runs the real `mvn` binary:

- **`publish`**: `mvn -B -ntp deploy -s settings.xml` with a fresh, empty
  `-Dmaven.repo.local` and `-Dmaven.repo.local.tail` pointed at a shared cache (below).
- **`resolve`**: `mvn -B -ntp dependency:get -Dartifact=<g>:<a>:<v>:jar
-DremoteRepositories=repsy::default::<repoUrl>` in a separate, also-fresh `-Dmaven.repo.local` —
  never the one `publish` used. `mvn deploy` also runs `install`, which copies the artifact into
  whatever local repo that invocation used, so reusing one repo between a test's own publish and
  resolve would let resolution succeed from that local copy without ever asking the Repsy server.

Third-party downloads (Maven's own core plugins and their dependencies from Maven Central) are kept
separate from that per-test local repo: `MAVEN_SHARED_REPO_DIR` (a named Docker volume,
`docker-compose.runners.yml`) is passed to every `mvn` invocation as `-Dmaven.repo.local.tail`, a
read-only fallback repo Maven consults before going to Central. A tail repo is never written to by a
fresh download (only read from), so `clients/maven.ts`'s `ensureSharedCacheWarm` primes it once —
guarded by an exclusive-create of a marker file, so it happens at most once per volume, not once per
test or per worker — with a throwaway `mvn package` (compiler/jar/install/deploy plugins) and a
throwaway `dependency:get` (the dependency plugin itself). This harness's own test artifacts never
end up in that shared cache: they always live under the unique `io.repsy.e2e.<runid>` groupId, which
a tail lookup can never already hold.

Because `mvn` hides the HTTP status behind its own exit code, `clients/maven.ts` also does a raw
HTTP PUT (publish) or GET (consume, of the repo root — see that file's comment on why the repo root
rather than the artifact's own resolved path) with the same credential, and derives the `Outcome`
from that raw status, not from `mvn`'s exit code (the spec asserts the two agree). Each `publish` also
packs a fresh random marker resource into the jar and reports the built jar's sha256; `resolve` reports
the sha256 and file name of the jar `dependency:get` left in its clean local repo (for a SNAPSHOT the
timestamped file, not the literal `-SNAPSHOT` copy Maven also writes), so a test can prove the consumer
got the very bytes that were deployed. The fixture's pre-publish runs only the real client (no raw
probe), so a later "repository unchanged" comparison sees exactly what a real deploy stored.

```bash
./run.sh test --protocol maven
```

## npm runner

`runners/npm.Dockerfile` adds nothing beyond the base image: npm ships with the `node:24-bookworm-slim`
base already used. `clients/npm.ts` renders `src/packages/npm/{package,npmrc,index}.template.*` into
a per-invocation isolated work directory (`clients/exec.ts`) and runs the real `npm` binary:

- **`publish`**: `npm pack --pack-destination <isolated dir>` (deterministic, no network) followed by
  `npm publish <that tarball> --userconfig <isolated .npmrc> --cache <isolated dir> --ignore-scripts`,
  `--force` iff the scenario is about a redeploy (`reuseCoordinates`, correction #1 of the plan: this
  bypasses npm 11's client-side "cannot publish over previous version" pre-flight, so the real request
  reaches the server whether the redeploy is allowed or refused).
- **`resolve`**: `npm install <name>@<version>` in a separate, also-fresh work directory with its own
  isolated `.npmrc`/cache, `--ignore-scripts --no-save --no-package-lock`.
- **`.npmrc`** (correction #4): `registry=<repoBaseUrl>/<repo>/` plus a matching
  `//<host:port>/<repo>/:_authToken=` (a deploy token, `MaterializedCredential.kind === 'token'`) or
  `:_auth=` (a real password, `kind === 'password'`) line — both need the SAME trailing slash to
  match under npm's per-path scoping (verified against
  [the npmrc docs](https://docs.npmjs.com/cli/v11/configuring-npm/npmrc)). **Every value interpolated
  into `.npmrc` or `package.json` uses triple-mustache (`{{{...}}}`), never `{{...}}`**: mustache's
  default double-brace interpolation HTML-escapes `/` and `=` (`&#x2F;`, `&#x3D;`), which is invisible
  inside maven's XML templates (numeric character references there round-trip correctly through any
  XML parser) but corrupts a plain `.npmrc`/`package.json` file outright — this broke every npm
  scenario the first time this step ran it live (a corrupted `registry=` line and a scoped package
  name with a literal `&#x2F;` in it), and is exactly the kind of thing only running the real client
  against a real instance catches.
- **Version scheme** (correction #3): `0.<seconds since 2026-01-01T00:00:00Z>.<seq>`, not a
  14+-digit `Date.now()`-derived number — `PackageUtils.extractVersionNameFromPayload` parses with
  semver4j 3.1.0, which stores each part as a Java `Integer`.
- Because `npm` hides the HTTP status behind its own exit code, `clients/npm.ts` also does a raw HTTP
  companion probe with the same credential: `publish`'s is a `PUT` of the identical publish document
  `npm publish` just sent (same packed tarball bytes, so a successful client publish followed by the
  raw re-PUT is a harmless, byte-identical redeploy of the version it just created, exactly like
  `clients/maven.ts`'s raw POM re-PUT); `resolve`'s is a packument `GET`, deliberately never a tarball
  fetch (see "RPS-1205", below) so its status is a clean authn/authz signal on its own.
- **Fingerprint** (`ProtocolAdapter.fingerprint`/`expectNothingStored`): npm has no repo-wide
  directory listing the way maven's does, so this is scoped to the one package a scenario's publish
  targets — the packument body's hash (`undefined` when the package does not exist at all) plus every
  version's stored tarball hash (by its canonical path, never `dist.tarball`), read with
  `npm-raw.ts`'s `rawGetPackument`/`parsePackument`/`rawGetTarballCanonical`, not the panel API.

```bash
./run.sh test --protocol npm
```

### Scenario mapping onto the shared catalog

Every catalog scenario that is not maven-restricted (`protocols: ['maven']` — the release/snapshot
and SNAPSHOT-redeploy scenarios) applies to npm unchanged, with **no `expectByProtocol` override
needed**: `ProtocolAuthService`/`ErrorHandler` (auth, throttle, status-code mapping) are shared with
maven verbatim, so every auth scenario's `Outcome` bucket (`unauthorized`/`forbidden`/`ok`) is
identical, even though the exact msgId sometimes differs (see below). The override rule
(`AbstractNpmProtocolFacade.publish`) and the malformed-version check
(`PackageUtils.extractVersionNameFromPayload`) were read from source first and then confirmed live
(`tests/npm/registry-rules.spec.ts`):

| scenario (shared catalog)                                                                      | real status observed              | msgId / note                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| ---------------------------------------------------------------------------------------------- | --------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `token-ro` publish                                                                             | `401 unauthorized`                | same as maven: a read-only deploy token attempting a write throws the plain `UnAuthorizedException` `ProtocolAuthService.authorizeDeployToken`/`authorizeDeployTokenRequest` always throws for that, not a distinct "forbidden"                                                                                                                                                                                                                                                                                                                                                                                                                 |
| `token-expired`/`token-revoked`/`token-rotated-old`/`token-other-repo` (Bearer, correction #5) | `401`, all four                   | The raw deploy-token secret is sent as npm's Bearer `_authToken`. `ProtocolAuthService.handleBearerAuth` tries it as a deploy token first (`tryAuthorizeWithDeployToken`) exactly like Basic's password; when that lookup finds nothing (revoked = row gone, rotated-old = value changed, other-repo = wrong repo id), it falls through to plain JWT verification of that same string, which fails to parse and throws `UnAuthorizedException(accessNotAllowed)` — 401 either way, so the shared `expect` needs no override, but (per correction #5) this fallback path never calls `AuthFailureThrottle`, unlike maven's Basic-auth equivalent |
| `no-override` (2nd publish, `allowOverride:false`)                                             | `403 packageVersionAlreadyExists` | `AbstractNpmProtocolFacade.publish`: `packageVersionOptional.isPresent() && !repoInfo.isAllowOverride()`                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                        |
| `override` (2nd publish, `allowOverride:true`)                                                 | `200 ok`                          | a NEW version of an existing package is _always_ accepted regardless of `allowOverride`; only re-publishing an _existing_ version is checked                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| everything else (`password-admin`, `token-rw`, `anonymous-public`, ...)                        | matches the shared `expect`       | unchanged from maven                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                            |

`registry-rules.spec.ts` additionally pins an invalid/malformed version string at `400
invalidPackageVersion` (`PackageUtils.extractVersionNameFromPayload`, before anything is stored).

### RPS-1205 (fixed): the exact shape the bug used to have

`PackageUtils.fixTarballUrl` (`repsy-protocols/npm/.../shared/utils/PackageUtils.java`) used to
rewrite a version's `dist.tarball` at publish time by splicing the repo name into the URL's path at a
fixed offset, a transform whose own javadoc described a cloud, multi-tenant path shape
(`/npm/username/@foo/demo/-/@foo/demo-0.2.1.tgz`) Repsy OS does not have. On OS, a real npm client's
own `dist.tarball` (computed client-side as `<registry>/<name>/-/<tarballFilename>`, i.e. already
just `/<repoName>/<packagePath>/-/<file>`) got a **second, wrong `/<repoName>/` segment spliced into
the middle of the path**. Live evidence from one run, before the fix (`tests/npm/registry-rules.spec.ts`):

```
canonical path "e2e-y50b4j9002-tarball/-/e2e-y50b4j9002-tarball-0.22833922.2.tgz" -> 200
dist.tarball   "http://localhost:9090/e2e-y50b4j9002-npm-1/e2e-y50b4j9002-tarball/e2e-y50b4j9002-npm-1/-/e2e-y50b4j9002-tarball-0.22833922.2.tgz" -> 404
```

(`e2e-y50b4j9002-npm-1` — the repo name — appeared twice: once correctly, as the request's own repo
segment, and once spliced in mid-path by `fixTarballUrl`.) The **canonical path always served the
real, byte-correct tarball** (confirmed by content hash, not just status); **`dist.tarball` always
answered `404`**, confirmed on unscoped and scoped packages alike (`tests/npm/publish-consume.spec.ts`'s
scoped-package test). This is what made RPS-1205 a URL-construction bug, not a storage one.

**Fixed**: `fixTarballUrl` now rebuilds only the filename after the last `/-/` from the version's own
`name`/`version`, and leaves everything before it — the client-computed path — untouched. That is a
no-op for the URL a real npm client already sends (the shape shown above as "canonical path"), so
`dist.tarball` now matches it and is servable, on unscoped and scoped packages alike. `npmAdapter` no
longer has a `knownConsumeFailure`, and `tests/npm/registry-rules.spec.ts`'s pin and
`tests/npm/publish-consume.spec.ts`'s scoped round trip both assert this for real now instead of
through `test.fail()`.

### RPS-1211 (fixed): redeploying a version without `keywords`

Re-publishing (redeploying, `allowOverride: true`) an **existing** npm version whose manifest has no
`keywords` field used to crash with `400 badRequest`, swallowing a `ClassCastException`:
`PackageUtils.liftFieldsToTopLevel` defaulted an absent version `keywords` onto the **top-level**
packument as a native `new String[] {}`; `NpmPackageServiceImpl.updateVersionFromMetadata` (reached
only on a re-publish of an _existing_ version, via `AbstractNpmProtocolFacade.publish`'s "already
exists" branch) then called `addKeywords`/`addMaintainers` with that **top-level** payload instead of
the version's own sub-object, and `addKeywords` cast what it found at `"keywords"` to
`ArrayList<String>` — which threw, because the value was a `String[]`, not an `ArrayList`, when it
came from that default. A first-ever publish of a package never hit this (`addPackage`'s DB path
passes the version's own sub-object, which legitimately has no `"keywords"` key, so the read is
`null` and skipped safely); only a **redeploy of an already-existing version** did. This is **not**
the same bug as RPS-1205 (a different bug, a different code path, no relation to tarball URLs).

**Fixed**: `PackageUtils.liftFieldsToTopLevel` now defaults `keywords` to an empty `ArrayList`
instead of a `String[]` (same `[]` on the wire), and `NpmPackageServiceImpl.addKeywords`/
`addMaintainers` read their input through an `instanceof Collection<?>` guard instead of an unchecked
cast, so no shape can throw there again. `clients/npm-raw.ts`'s `buildPublishDocument` and
`src/packages/npm/package.template.json` still always include `"keywords": []` (a real npm client's
own normalised manifest almost always does too, and removing it to add a no-`keywords` redeploy
scenario is tracked as a small follow-up, not required for this fix to be effective).

## Cargo runner

`runners/cargo.Dockerfile` copies a pinned Rust toolchain (`rust:1.98.1-slim-bookworm`, latest
stable per the Rust blog, `-slim` so no gcc lands in the image) in from that official image's
`/usr/local/{rustup,cargo}` directories rather than installing it by hand — `/usr/local/cargo/bin/
{cargo,rustc}` are rustup proxies that resolve the real toolchain via `RUSTUP_HOME` at run time, so
both directories have to come along, not just the proxies. `clients/cargo.ts` renders
`src/packages/cargo/{Cargo,lib,consumer-Cargo,config}.template.*` into a per-invocation isolated
work directory (`clients/exec.ts`) and runs the real `cargo` binary:

- **`publish`**: `cargo package --no-verify --offline` (deterministic, no network — pre-packages the
  crate so a raw-probe body exists even when the real `cargo publish` never gets far enough to
  produce one itself, e.g. an auth failure refuses the client before it packages anything) followed
  by `cargo publish --registry repsy --no-verify`. `--no-verify` is deliberate on both: verifying
  builds the crate, which needs `rustc` and a linker — a build is the toolchain's concern, not the
  registry's, and this harness's crates are dependency-free and never need to actually compile.
  `CARGO_PUBLISH_TIMEOUT=30` bounds cargo's own post-publish index poll (default 60s) — confirmed
  live, an underscore-named crate's poll resolves on its first attempt (well under a second). A
  hyphenated crate's poll used to burn the whole 30s window before RPS-1212 was fixed (see "H2"
  below); now it also resolves on its first attempt, so nothing here relies on the longer default
  any more — the lower bound is kept as a safety margin, not because anything still needs it.
- **`resolve`**: a fresh, separate work directory (fresh `CARGO_HOME` too) with a minimal consumer
  crate (`[dependencies] <name> = { version = "=<version>", registry = "repsy" }`, an empty
  `src/lib.rs` — cargo refuses a package with no targets at all) and a plain `cargo fetch`. The
  downloaded `.crate` lands at `<CARGO_HOME>/registry/cache/<registry-hash>/<name>-<version>.crate`;
  `cargo fetch` itself verifies its sha256 against the index `cksum`, so a mismatch fails the client
  too, before this harness's own comparison ever runs.
- **`.cargo/config.toml`** (rendered into the work directory, not `CARGO_HOME`'s own global config —
  cargo looks upward from its cwd for this file): `[registries.repsy] index =
"sparse+<repoBaseUrl>/<repo>/"` (the trailing slash is mandatory) plus an explicit `[registry]
global-credential-providers = ["cargo:token"]`.
- **Token delivery**: the credential is delivered ONLY via the `CARGO_REGISTRIES_REPSY_TOKEN` env
  var (never written to a credentials file, never on the command line — nothing for `exec.ts`'s
  redaction to miss). Cargo's `cargo:token` provider sends that env var's value **verbatim** as the
  `Authorization` header, with no `Bearer`/`Basic` prefix of its own; `CargoAuthPreProcessor
.normalizeAuthHeader` on the server prefixes it with `Bearer ` unless it already starts with
  `Basic `/`Bearer `. That is what makes a `password`-kind credential expressible through cargo's
  single "token" concept at all: `cargoAuthHeader`/`cargoToken` (`cargo-raw.ts`) send `Basic
<base64(user:pass)>` as that literal env var value for a real user/admin password — confirmed live,
  not just from the Cargo source citation (`crates-io/lib.rs`'s `check_token` accepts a space
  character, RFC 9110 field-value ASCII, so a Basic header is a syntactically legal cargo token, and
  the server dispatches it to `handleBasicAuth` unchanged since it already starts with `Basic `). A
  `token`-kind credential (a deploy token, what the panel's own cargo docs tell users to `cargo
login` with) is sent raw, with no prefix at all.
- **The publish-side raw probe is NOT a re-PUT of the version the client just published** (unlike
  maven's POM re-PUT and npm's tarball re-PUT): cargo's publish route has no override rule at all
  (confirmed live, see "H1" below — every duplicate-version PUT is refused unconditionally), so
  re-sending the just-published version would always come back `rejected`, never `ok`, breaking
  every scenario whose publish is supposed to succeed. Instead: a non-redeploy scenario probes a
  **prerelease sibling**, `<version>-probe` (accepted — semver orders a prerelease below its bare
  version, so it never disturbs `max_version` and an `=<version>` consumer never resolves to it,
  confirmed live); a redeploy scenario (`reuseCoordinates`, `no-override`/`override`) re-PUTs the
  EXACT version the client just attempted — precisely what a real client would have hit had it not
  refused client-side first (see "H1" below for what that then proves about storage).
- **The consume-side raw probe** is a sparse-index `GET`, deliberately never the crate download,
  mirroring npm's packument-GET reasoning: every consume expectation in the catalog is an authn/authz
  outcome, and an index GET never touches the (possibly storage-corrupted, "H1" below) `.crate`
  bytes.
- **Fingerprint** (`ProtocolAdapter.fingerprint`/`expectNothingStored`): scoped to the one crate a
  scenario's publish targets, like npm's — the served sparse index's own hash (`undefined` when the
  crate does not exist at all) plus every listed version's downloaded `.crate` content hash, read
  with `cargo-raw.ts`'s `rawGetIndex`/`parseIndex`/`rawDownload`, not the panel API.
- **Underscore-only crate names** (`cargoAdapter.packageName`, `cargo-raw.ts`'s `crateName`): the
  catalog loop deliberately never uses a hyphenated name — see "H2" below for why.

```bash
./run.sh test --protocol cargo
```

### Scenario mapping onto the shared catalog

Every catalog scenario that is not maven-restricted applies to cargo unchanged, with the same
`unauthorized`/`ok` buckets as maven and npm (`ProtocolAuthService`/`CargoAuthPreProcessor` share the
same throw-on-any-auth-failure shape), EXCEPT the override pair, which cargo has no rule for at all:

| scenario (shared catalog)                                                                             | real status observed        | note                                                                                                                                                                                                      |
| ----------------------------------------------------------------------------------------------------- | --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `no-override` (2nd publish, `allowOverride:false`)                                                    | `400 rejected`              | `CargoCrateServiceImpl.checkExistsVersion` throws unconditionally — `allowOverride` is never read by the protocol at all (`expectByProtocol: { cargo: { publish: 'rejected' } }`, `catalog.ts`)           |
| `override` (2nd publish, `allowOverride:true`)                                                        | `400 rejected`              | deliberately still `rejected`: this is exactly what the scenario pins — `allowOverride: true` does NOT make cargo accept a version override                                                               |
| `token-ro` publish                                                                                    | `401 unauthorized`          | same as maven/npm: `ProtocolAuthService.authorizeDeployToken` throws the plain `UnAuthorizedException`, not a distinct "forbidden"; the client fails at its own index-query preflight before ever PUTting |
| `token-expired`/`token-revoked`/`token-rotated-old`/`token-other-repo`/`wrong-password`/`anonymous-*` | `401`, all                  | fails at the client's OWN preflight index GET (`config.json` is unauthenticated even on a private repo, but the index GET that follows needs the token) before any publish PUT is ever sent               |
| everything else (`password-admin`, `token-rw`, `anonymous-public`, ...)                               | matches the shared `expect` | unchanged from maven/npm                                                                                                                                                                                  |

`registry-rules.spec.ts` additionally pins an invalid/int-overflowing version string at `400` with a
`"not a valid semver format"` detail (`CrateUtils.validateVersion`, semver4j 3.1.0 — the same library
version, and the same Java-`Integer` overflow reasoning, as npm's correction #3; this is why
`coordinates.ts`'s `boundedSemverVersion` is shared between the two adapters), and `config.json`'s
own `dl`/`api`/`auth-required` shape.

### H1 (RPS-1124, fixed): a refused duplicate publish used to corrupt storage

`AbstractCargoProtocolFacade.publish` used to write the `.crate` bytes to storage
(`FileSystemStorageStrategy.write`, `TRUNCATE_EXISTING` — overwrites whatever was already there) and
append an index line to a storage-only index FILE **BEFORE** calling `CargoCrateServiceImpl.publish`,
where the duplicate-version check (`checkExistsVersion`) lives. When that check threw, the DB
transaction rolled back but the storage write had already happened. Live evidence at the time
(`tests/cargo/registry-rules.spec.ts`):

```
seed publish (bytesA)                              -> 200
download after seed                                 -> 200, equals bytesA
duplicate publish (bytesB, allowOverride: false)    -> 400 "this crate version already exists in this registry"
served (DB-backed) sparse index after the duplicate -> unchanged (still bytesA's cksum)
download after the duplicate                        -> 200, equals bytesB, NOT bytesA   (before the fix)
```

**Fixed** by RPS-1124 (#507: the version rows are written first, in one transaction, and the crate
file only afterwards). The download after a refused duplicate is now bytesA again, and
`registry-rules.spec.ts`'s override test and the catalog loop's `no-override`/`override` scenarios
(`adapter.expectNothingStored`) assert that for real. `cargoAdapter` no longer sets
`knownPublishSideEffect`; the hook itself stays in `scenarios/adapter.ts`/`scenarios/loop.ts`
(the publish-side analogue of npm's old `knownConsumeFailure`) for the next such bug.

### H2, confirmed live: the sparse index serves a crate under its normalised name (RPS-1212)

`CrateUtils.normalizeCrateName` (lower-case, `-` -> `_`) is applied both when a crate is stored
(`CargoCrateServiceImpl.publish` stores the crate row and its index row under the normalised name)
and whenever it is looked up by name (`getIndexEntries`/`findCrate` normalise their own `name`
argument before querying) — so a raw HTTP GET of a hyphenated crate's sparse index, by EITHER its
hyphenated or its normalised spelling, answers `200` with the SAME entry, whose own `"name"` field
always says the normalised spelling. A real `cargo publish` of a hyphenated name still exits 0 (its
own post-publish index poll looks the crate up by the exact name it sent, and the server-side lookup
normalises that too, so the poll does find an entry) — but slowly: confirmed live, ~30s (the full
`CARGO_PUBLISH_TIMEOUT` window) instead of well under a second for an underscore-only name, before
falling back to a "timed out waiting for ... to be available" WARNING (not a failure). A real `cargo
fetch` of that same hyphenated name, however, does NOT trust the file location the way the raw probe
does: it cross-checks the served entry's own `name` field against the dependency name it declared and
refuses outright — confirmed live:

```
cargo publish (crate "e2e-<runid>-hyphen")  -> exit 0 (slow: ~30s post-publish poll timeout)
raw index GET (hyphenated spelling)         -> 200, "name":"e2e_<runid>_hyphen"
raw index GET (normalised spelling)         -> 200, "name":"e2e_<runid>_hyphen" (same entry)
cargo fetch (dependency "e2e-<runid>-hyphen") -> exit 101, "error: no matching package named
                                                  `e2e-<runid>-hyphen` found"
```

This is why the catalog loop's own crate names are underscore-only (`cargoAdapter.packageName`,
`cargo-raw.ts`'s `crateName` — never `scenarios/coordinates.ts`'s hyphenated `slugify`): a hyphenated
name used to round-trip through the loop under a spelling the server itself never agreed to serve
back. Filed as **RPS-1212**.

**Fixed**: `CargoCrateConverter.toCrateIndexEntry` now maps the served entry's `name` from
`CargoCrate.originalName` (already persisted at publish time, unused by the converter until now)
instead of the normalised lookup key. Lookup stays spelling-insensitive (both the hyphenated and the
normalised spelling still answer `200`), but the served entry's identity is now stable and always
names the spelling the crate was actually published under. `cargo publish` of a hyphenated crate now
confirms on its first post-publish poll (no more ~30s stall), and `cargo fetch` resolves it instead
of refusing with "no matching package". `tests/cargo/publish-consume.spec.ts`'s dedicated real-client
test and `tests/cargo/registry-rules.spec.ts`'s raw-HTTP test both assert this directly now (the
`test.fail()` pins are gone).

### Cargo protocol-specific suite (steps 5b/5c, RPS-294)

`tests/cargo/protocol-specific.spec.ts`, ported from `repsy-cloud`'s own e2e harness (read-only
reference material, never a target this repo modifies) into real `cargo` binaries + hand-built
`World`-less crate layouts, exactly this harness's own conventions — never that harness's own `npx
tsx subprocess`/`shelljs` structure. "Cargo A" (step 5b: `library/test.ts`'s yank/unyank/search parts,
`checksum/test.ts`, `multi_version/test.ts`) uses a fresh `token-rw` deploy-token credential built by
hand around `cargo.publish(world)`; "Cargo B" (step 5c: `dependency_tree`/`platform_deps`/`workspace`)
adds crates with real inter-crate dependencies, published with the real `cargo publish` binary
directly (no `cargo.publish(world)`/`packageCrate()` — see below). Every crate/workspace-member name
in this file is underscore-only, routing around RPS-1212 above by construction rather than
re-tripping it.

- **Yank/unyank** (`DELETE`/`PUT /<repo>/api/v1/crates/<name>/<version>/(yank|unyank)`,
  `AbstractCargoYankProtocolMethodHandler`, `permission: WRITE`): confirmed live with the real `cargo
yank`/`cargo yank --undo` binaries — both exit `0`, the served sparse-index entry's `yanked` field
  flips accordingly, and a **yanked** crate's `.crate` bytes are still downloadable afterwards
  (`AbstractCargoDownloadProtocolMethodHandler` has no yanked check at all) — matching real Cargo
  semantics: yank affects fresh dependency resolution only, never a download of an already-pinned
  version. A read-only deploy token's yank attempt is refused with a real, live `401 unAuthorized`
  (both the real client and a raw probe) — the documented `permission: WRITE` is actually enforced.
- **Search** (`GET /<repo>/api/v1/crates?q=<query>`, `AbstractCargoSearchProtocolMethodHandler`,
  `permission: READ`): confirmed live with the real `cargo search --registry repsy` binary — exit `0`,
  stdout lists the crate, and the raw envelope (`{"crates":[...],"meta":{"total":N}}`) matches.
- **Owners**: `GET/PUT/DELETE /<repo>/api/v1/crates/<name>/owners` used to be one handler
  (`CargoOwnersProtocolMethodHandler` — defined directly in `repsy-backend`, unlike every other cargo
  route, which extends a shared abstract class in `repsy-protocols/cargo`) answering **every** owners
  request, even a GET, with a FIXED body
  (`{"ok":true,"msg":"Ownership is managed at the repository level in this registry"}`) and
  `permission: WRITE` even for the GET (which also meant `getProperties()` omitted `writeOperation`,
  so PUT/DELETE skipped authentication entirely on a public repo). There was no `users` array at all,
  so a real `cargo owner --list --registry repsy <crate>` failed client-side ("missing field `users`",
  exit `101`) even though the raw HTTP GET itself succeeded (`200`) — confirmed live, filed as
  **RPS-1239**.

  **Fixed**: the route is split into `CargoOwnersListProtocolMethodHandler` (GET,
  `permission: READ`) and `CargoOwnersModifyProtocolMethodHandler` (PUT/DELETE, `permission: WRITE`,
  `writeOperation: true`). GET now answers the crates.io `{"users": [...]}` shape with a repo-level
  synthetic owner (`{"id":0,"login":"<repoName>","name":"Ownership is managed at the repository level
in this registry"}"}`, since Repsy has no ownership model finer than the repository); PUT/DELETE
  keep the original `{"ok":true,"msg":"..."}` body, but are now real write operations that
  authenticate even on a public repo — a permission-narrowing behavior change on what was previously
  an (unauthenticated, no-op) "working" path. A real `cargo owner --list` now succeeds.

- **Index `cksum` / multi-version**: confirmed live that a crate's served sparse-index `cksum` equals
  the sha256 of the raw-downloaded `.crate` bytes (and the adapter's own publish hash), and that two
  versions of one crate coexist independently — distinct `cksum`s, distinct downloaded bytes, both
  present in the index simultaneously.
- **Real inter-crate dependencies need an ONLINE `cargo publish`, not `packageCrate()`'s offline
  `cargo package`**: `clients/cargo.ts`'s own `packageCrate()` runs `cargo package --no-verify
--offline` for the catalog loop's dependency-free marker crate — confirmed live that this FAILS
  ("no matching package named ... found ... offline mode ... can sometimes cause surprising
  resolution failures") the moment a crate declares a real dependency on another crate already
  published to the same repo. A plain `cargo publish --registry repsy --no-verify --allow-dirty`
  (online, no separate `cargo package` step at all) resolves the dependency against the registry and
  succeeds. `tests/cargo/protocol-specific.spec.ts`'s own `publishRealCrate()` helper uses this form
  directly — `clients/cargo.ts` itself is untouched (the catalog loop's own dependency-free path never
  needed to change).
- **Dependency tree** (leaf → mid → root, each declaring a Repsy-registry dependency on the previous):
  confirmed live that publishing leaf-first works end to end, and that a same-registry dependency's
  served `deps` entry carries a `req` containing the exact dependency version and has **no
  `"registry"` field at all** — real Cargo's own manifest/publish serialization for a same-registry
  dependency, not a Repsy-specific behaviour.
- **Platform-specific dependency**: a `[target.'cfg(unix)'.dependencies]` entry publishes and is
  served with a `target` field on its `deps` entry containing `cfg(unix)`, confirmed live.
- **Workspace**: a 3-member workspace (utils → core → app, each `cargo publish --package <member>`
  from the workspace root, no compilation anywhere — the runner image ships no gcc/build-essential)
  publishes cleanly member-by-member in dependency order; each member's served `deps` correctly names
  every crate it depends on.

## NuGet runner

`runners/nuget.Dockerfile` copies a pinned .NET SDK (`mcr.microsoft.com/dotnet/sdk:10.0.401-noble`,
current .NET 10 LTS per `dotnet/dotnet-docker`'s `README.sdk.md`) in from that official image's
`/usr/share/dotnet` directory rather than installing it by hand — there is no Debian image for the
.NET 10 SDK at all (`noble`/`resolute`/`alpine`/`azurelinux` only; 9.0 was the last SDK with a
`bookworm-slim` tag), but the noble image itself only untars the portable `linux-x64` SDK build into
that one directory, and Debian 12 is a supported .NET 10 OS, so the copy works unmodified into the
`node:24-bookworm-slim` base every other runner uses.
`DOTNET_SYSTEM_GLOBALIZATION_INVARIANT=1` avoids coupling the image to a specific ICU package
version (this harness's rendered packages/projects carry no culture-sensitive data), the same
approach the official Alpine .NET images take. `clients/nuget.ts` builds a `.nupkg` directly with
`fflate` (`nuget-raw.ts`'s `buildNupkg` — **no `dotnet pack`, no build, no `[Content_Types].xml`**:
a zip containing a root `<id>.nuspec` and `content/e2e-marker.txt`, which is all the server's own
`.nuspec`-extraction regex and the real client's package reader need) and runs the real `dotnet`
binary:

- **`publish`**: `dotnet nuget push <nupkg> --source repsy --configfile <nuget.config>
--allow-insecure-connections --no-symbols --timeout 60 [--api-key <k>]` against the pre-built nupkg —
  no packaging step to precede it, unlike cargo/maven, since the nupkg bytes are already on disk
  before the client ever runs. `k` is the raw deploy token for a `token`-kind credential (the panel's
  "Option B" for a token), or the literal string `any` for a `password`-kind credential (the panel's
  "Option B" text VERBATIM — confirmed live to be a dummy value, see "H7" below); `anonymous` gets no
  `--api-key` at all.
- **`resolve`**: a fresh, separate work directory (fresh `NUGET_PACKAGES`/`HOME` too) with a minimal
  consumer **classlib** project (`net10.0`, `<PackageReference Include="<id>" Version="[<version>]"
/>`, no `Program.cs`/apphost needed) and `dotnet restore <consumer.csproj> --configfile <nuget.config>
--packages <isolated dir> --no-http-cache --disable-build-servers -p:NuGetAudit=false -v minimal`.
  `net10.0`'s targeting pack ships inside the SDK image's own `packs/` directory, and `<clear/>` in
  every rendered `nuget.config` guarantees nothing is ever fetched from nuget.org — confirmed live,
  H3 below. The restored `.nupkg` lands at `<NUGET_PACKAGES>/<idLower>/<verLower>/
<idLower>.<verLower>.nupkg`, kept whole (never unpacked into the consumer's own tree), so
  `AdapterResult.contentSha256` is the sha256 of that WHOLE file, exactly like cargo's `.crate`.
- **`nuget.config`** (rendered fresh into an isolated `HOME` per invocation, one shared shape for
  BOTH push and restore — never `dotnet`'s own machine-wide config): `<clear/>` plus one `repsy`
  source (`protocolVersion="3" allowInsecureConnections="true"`) and, whenever the credential carries
  a Basic pair (both `token`- and `password`-kind credentials do — a deploy token's username + token
  value, or a real user/admin username + password), a `packageSourceCredentials` block with that same
  pair. `anonymous` renders no credentials block at all.
- **A real override rule, with a real `409`**: `AbstractNuGetProtocolFacade.publish` refuses
  `!allowOverride && versionExists` with `409 Conflict` ("Version `<v>` of package `<id>` already
  exists.") — the first protocol in this harness where the catalog's `conflict` outcome is exercised
  for real (maven: 403; npm: always refused regardless of `allowOverride`, also 403; cargo: no
  override rule at all, unconditional 400). `checkVersionAllowance` (releases/snapshots) runs BEFORE
  that check, so a redeploy of an existing version under `releases: false`/`snapshots: false` is
  `422`, never `409` — confirmed live, see the registry-rules test below.
- **The publish-side raw probe IS a byte-identical re-PUT** of the exact nupkg the client just pushed
  (the maven/npm pattern, unlike cargo's prerelease-sibling workaround): every `ok`-expected scenario
  runs with `allowOverride: true` (the fixture default), so the re-PUT is an accepted, identical
  replacement; `no-override` gets the same `409` the client got; a releases/snapshots refusal gets
  the same `422`.
- **The consume-side raw probe** is a flat-version-list `GET` (`v3/package/<idLower>/index.json`),
  mirroring npm's packument-GET / cargo's sparse-index-GET reasoning: every consume expectation in
  the catalog is an authn/authz outcome, and this GET never touches the `.nupkg` bytes.
- **Fingerprint** (`ProtocolAdapter.fingerprint`/`expectNothingStored`): scoped to the one package a
  scenario's publish targets, like npm's/cargo's — the flat-version-list body hash (`undefined` when
  the package does not exist at all) plus every listed version's downloaded `.nupkg` content hash.
- **No `knownConsumeFailure`/`knownPublishSideEffect`**: H4 (below) found no RPS-1124-style storage
  corruption on a refused publish, and H5 found no RPS-1205-style broken URL — both checked live
  before being left out, not assumed from the plan.

```bash
./run.sh test --protocol nuget
```

### Scenario mapping onto the shared catalog

Every catalog scenario that is not maven-restricted applies to nuget unchanged, with the same
`unauthorized`/`ok` buckets as every other protocol (`NuGetAuthPreProcessor` throws the same flat
`401` + `WWW-Authenticate: Basic` challenge for every authn/authz failure — there is no separate
"forbidden" outcome here either), EXCEPT the override pair and the four releases/snapshots
scenarios, which nuget has REAL rules for (unlike cargo's "no rule at all" or maven's 403s):

| scenario (shared catalog)                                                             | real status observed        | note                                                                                       |
| ------------------------------------------------------------------------------------- | --------------------------- | ------------------------------------------------------------------------------------------ |
| `no-override` (2nd publish, `allowOverride:false`)                                    | `409 conflict`              | `AbstractNuGetProtocolFacade.publish`, a REAL conflict — the first protocol here to use it |
| `override` (2nd publish, `allowOverride:true`)                                        | `201 ok`                    | the row is replaced and the file is overwritten; no `expectByProtocol` override needed     |
| `maven-releases-off`/`redeploy-releases-off` (added to nuget's `protocols`)           | `422 rejected`              | `checkVersionAllowance` — a nuget "release" is any version without a `-` prerelease label  |
| `maven-snapshots-off`/`redeploy-snapshots-off` (added to nuget's `protocols`, `-pre`) | `422 rejected`              | a nuget "snapshot" is a `-pre` prerelease label, not a Maven-style timestamped SNAPSHOT    |
| `token-ro` publish                                                                    | `401 unauthorized`          | same shape as every other protocol: every auth failure maps to a flat 401                  |
| everything else (`password-admin`, `token-rw`, `anonymous-public`, ...)               | matches the shared `expect` | unchanged                                                                                  |

`snapshot-deploy`/`snapshot-redeploy*` stay maven-only (`protocols: ['maven']`): they are about
Maven's timestamped-SNAPSHOT-file semantics (`buildNumber`, version-level `maven-metadata.xml`),
which nuget has no equivalent of — a nuget "snapshot" is stored once, like any other version.

`registry-rules.spec.ts` additionally pins: a duplicate-version publish leaving storage untouched
(H4); a new version always accepted, an existing one replaced only when `allowOverride: true`; the
redeploy-under-`releases:false` ordering (422 before 409); invalid/too-long/5-part version strings
and non-package/non-multipart bodies at 400; `1.0.0.0` normalized to `1.0.0` on both the flat index
and the download path; the service index's exact `@id`/`@type` shape, now the bare,
client-recognised types RPS-1213 fixed (H6); and `X-NuGet-ApiKey`'s three cases (H7).

### H1-H13, confirmed live

Every hypothesis below was probed against a running instance (`./run.sh local up`) before being
pinned — first with raw HTTP (`tsx` scripts using `nuget-raw.ts` directly), then with the real
`dotnet` client inside the `nuget` runner container. All were confirmed **exactly as the plan
predicted**; none required a workaround or a routing-around hook.

- **H1** (a `password`-kind push succeeds through the 401→Basic retry on a chunked multipart PUT):
  confirmed — every scenario using `admin-password`/`user-password` (`--api-key any` plus
  `packageSourceCredentials`) publishes successfully with the real `dotnet nuget push`; no
  `"Basic <base64>"`-as-api-key fallback was needed.
- **H2** (whether `--allow-insecure-connections` is needed in addition to
  `allowInsecureConnections="true"` on the source): both are present in the final implementation and
  every push/restore succeeds; this step did NOT separately ablate one flag to prove the other alone
  suffices (time-boxed) — left as a known, harmless redundancy rather than an unconfirmed guess about
  which one is load-bearing.
- **H3** (the hand-assembled nupkg both pushes and restores; nothing is fetched from nuget.org):
  confirmed — every scenario's `dotnet restore` exits 0 against a `<clear/>` source with no
  third-party dependency, and the restored file's sha256 matches the published one
  (`expectResolvedContent`, `scenarios/loop.ts`).
- **H4** (no storage side effect on a refused publish): confirmed, live —
  `tests/nuget/registry-rules.spec.ts`'s duplicate-version test shows the stored `.nupkg` unchanged
  (still bytesA, never bytesB) after a refused `409` duplicate. Unlike cargo's RPS-1124, the DB write
  and the storage write happen in the SAME call, after every refusal check throws, so there is
  nothing to corrupt. `ProtocolAdapter.knownPublishSideEffect` is left out entirely.
- **H5** (advertised URLs resolve on the single-tenant layout, one `/<repoName>/` segment): confirmed
  — the registration leaf's `packageContent` and the service index's `PackageBaseAddress/3.0.0`
  `@id` both name exactly `<repoBaseUrl>/<repoName>/v3/package[...]`, and `afterSuccessfulRoundTrip`
  (`nuget.ts`) GETs that exact URL and gets `200` with the published bytes on every successful
  scenario.
- **H6** (`dotnet package search`/registration-based commands fail; push/restore do not): originally
  filed as **RPS-1213** from the service index's exact `@type` shape — `RegistrationsBaseUrl/3.0.0`,
  `SearchQueryService/3.0.0`, `SearchAutocompleteService/3.0.0` and a non-standard
  `PackageDelete/2.0.0`, none of which NuGet.Client's `ServiceTypes.cs` recognised, while
  `PackageBaseAddress/3.0.0`/`PackagePublish/2.0.0` (what push/restore use) were already correct.
  **RPS-1213 fixed the registration type and dropped `PackageDelete/2.0.0`**; its bare
  `SearchQueryService`/`SearchAutocompleteService` replacement was not enough — **RPS-1240** found
  (live, .NET SDK 10.0.401 / NuGet.Client 7.9.0) that NuGet.Client's `ServiceTypes.cs` has no bare
  form for either: `SearchQueryService` is only `/Versioned`, `/3.4.0` or `/3.0.0-beta`,
  `SearchAutocompleteService` only `/Versioned` or `/3.0.0-beta` (only `RegistrationsBaseUrl` has a
  bare form; the service-index docs list the bare names, the client does not use them). Served by an
  otherwise identical index, bare, `/3.5.0` and `/3.0.0-rc` reproduce "The source does not have a
  Search service!" while `/3.0.0-beta` and `/3.4.0` make the client issue `GET
v3/search?q=...&semVerLevel=2.0.0`. The service index now advertises the bare types plus
  `/3.0.0-beta` for both, at the same URLs (the client queries the shared URL once), and
  `tests/nuget/protocol-specific.spec.ts`'s `dotnet package search` test runs the real client to
  completion (`tests/nuget/registry-rules.spec.ts`'s H6 test pins the served shape).
  **RPS-1275** made the search and autocomplete endpoints honour the `semVerLevel` parameter the
  client sends with every search: without it (or below `2.0.0`) SemVer 2.0.0-only versions (a
  dot-separated pre-release label, build metadata) and the packages that only have such versions
  are left out, as the search/autocomplete docs prescribe. `/3.4.0` is still not advertised (the
  docs define no such type for search, and `/3.5.0` would promise the `packageType` filter);
  `tests/nuget/protocol-specific.spec.ts`'s `semVerLevel` test pins the behaviour raw and through
  the real client. The registration index and flat container have no `semVerLevel` parameter in
  the docs and keep listing every version.
- **H7** (`X-NuGet-ApiKey: <user password>` → `401`, contradicting the panel's Option B text):
  confirmed live, exactly as predicted —
  `X-NuGet-ApiKey: <admin password>` → `401`; `X-NuGet-ApiKey: <deploy token>` → `201`;
  `X-NuGet-ApiKey: "Basic <base64(user:pass)>"` → `201` (the workaround, since
  `NuGetAuthPreProcessor.normalizeAuthHeader` leaves an already-`Basic `-prefixed value alone and
  dispatches it to the Basic path). Filed as **RPS-1214** (a frontend-docs fix, or a server-side
  "try the api key as a Basic password" fallback, is left as an open decision — not fixed here).
- **H8** (mixed-case id round trip): confirmed live —
  `tests/nuget/publish-consume.spec.ts`'s dedicated test publishes `E2E-<runid>-MixedCase` with the
  real client, then shows the flat index is only queryable by the LOWERCASED id and the registration
  echoes `catalogEntry.id` as that same lowercased spelling (`findOrCreatePackage` stores
  `packageId.toLowerCase()`), never the mixed-case one it was actually published under. The real
  client itself still round-trips correctly (`Include="E2E-<runid>-MixedCase"` resolves fine — NuGet
  package references are already case-insensitive on the client side).
- **H9** (timing fits the 120s test timeout): confirmed — every scenario in two full catalog runs
  completed in 1-2s (a positive scenario) to ~7-8s (a `@negative` one waiting out the auth
  throttle window), both runs finishing in ~14s total wall time for all 27 tests together, run in
  parallel across 12 workers.
- **H10** (a `-pre` prerelease version restores exactly and is listed): confirmed —
  `redeploy-snapshots-off` (the catalog's only `versionType: 'snapshot'` scenario reused for nuget)
  seeds and later consumes a `-pre` version with the real client end to end.
- **H11** (an anonymous push fails non-interactively, exit 1, no hang): confirmed — `anonymous-private`
  and `anonymous-public`'s publish attempts both fail the real client cleanly (no `--interactive`
  flag was ever needed) well within the timeout.
- **H12** (`0.0.<Date.now()>`, a version no real client could parse, is accepted server-side):
  confirmed live — `201`, listed verbatim as `0.0.<the exact digits>`. Pinned as an observation in
  `registry-rules.spec.ts`, not filed (the client-side bound is exactly why
  `coordinates.ts`'s `boundedSemverVersion` is still used for every real-client scenario).
- **H13** (parallel workers, no cross-test interference): confirmed — every isolated `HOME`/
  `NUGET_PACKAGES`/`NUGET_SCRATCH`/HTTP-cache directory is unique per invocation
  (`isolatedWorkDir`/`nugetEnv`), and two full 27-test runs (12 parallel workers each) both passed
  with no flakiness or leftover-state failures.

### NuGet protocol-specific suite (step 5e, RPS-294)

`tests/nuget/protocol-specific.spec.ts`, ported from `repsy-cloud`'s own e2e harness
(`protocols/nuget/unlist`/`relist`/`search`/the "explicitly older version" case of
`multi_version` — read-only reference material, never a target this repo modifies) into real
`dotnet` binaries and hand-built `World`s, this harness's own conventions — never that harness's
own `npx tsx subprocess`/`shelljs` structure. Every test uses a fresh `token-rw` deploy-token
credential built by hand, exactly like the Cargo protocol-specific suite above.

- **Unlist/relist** (`DELETE`/`POST /<repo>/v3/package/<idLower>/<verLower>`,
  `AbstractNuGetUnlistProtocolMethodHandler`/`AbstractNuGetRelistProtocolMethodHandler`,
  `permission: WRITE` both ways — NuGet's own `PackagePublish/2.0.0` convention, never the
  `PackageDelete/2.0.0` type the service index used to also advertise before RPS-1213 removed it):
  confirmed live — `DELETE` → `204`, the registration leaf's `listed` flips to `false`; `POST` →
  `200`, flips it back to `true`. Real NuGet semantics: unlisted != deleted, so the flat
  `v3/package/<id>/index.json` container keeps serving the version regardless of its `listed` state,
  and a fresh real `dotnet restore` of the exact unlisted version still succeeds end to end (not just
  a raw probe).
- **Search/autocomplete** (`GET /<repo>/v3/search`/`v3/autocomplete`, `permission: READ`): confirmed
  live over raw HTTP — search answers `{"totalHits":N,"data":[{"id","version","registration",...}]}`
  (`NuGetSearchResponse`/`NuGetSearchData`, `data[].id` the lowercased stored spelling, matched
  case-insensitively, same H8 fact as the rest of this runner's suite); autocomplete answers
  `{"totalHits":N,"data":["<idLower>",...]}` (`NuGetAutocompleteResponse`), bare id strings. Both
  routes work fine over raw HTTP — see the next point for why a real client still can't reach them.
- **`dotnet package search` (RPS-1213 + RPS-1240)**: a real `dotnet package search <id> --source
repsy --configfile <cfg>` against a package this suite had just published and proven searchable
  over raw HTTP (previous point) lists it. Before RPS-1240 it did NOT crash and did NOT exit non-zero
  — exit `0`, `error: The source does not have a Search service!`, no `v3/search` request — because
  NuGet.Client has no bare-type form for search (see H6 above for the exact vocabulary). The service
  index now advertises `SearchQueryService/3.0.0-beta` and `SearchAutocompleteService/3.0.0-beta`
  next to the bare types.
- **Explicitly older version restores**: publishing version B after version A, then explicitly
  restoring A (`renderConsumerProject`/`nuget.resolve` always pin an exact bracketed
  `Version="[<version>]"`) returns exactly A's bytes, never B's — confirmed live, no "latest wins"
  behaviour leaks into an explicit restore.

## Docker runner

`runners/docker.Dockerfile` copies the single static `crane` binary (go-containerregistry v0.22.1,
`/ko-app/crane` in its own distroless image) into the harness image — **no daemon, no
`docker.sock`, no `--privileged`, no DinD anywhere in this runner.** `clients/docker-image.ts`
builds a tiny, fully hand-assembled OCI image layout directly (`buildImage`: a hand-written ustar
tar with one `e2e-marker.txt` file, gzipped with `node:zlib`; a JSON config; a JSON manifest;
`index.json`; `oci-layout` — deliberately never `docker build`/`crane append`, the nuget/cargo
precedent of never shelling out to a tool this harness does not control the exact bytes of), and
`clients/docker.ts` runs the real `crane` binary against that directory:

- **`publish`**: `crane push <oci-layout-dir> <ref> [--insecure]` against the pre-built layout — no
  packaging step to precede it, like nuget's pre-built nupkg. `ref` is
  `<registryHost>/<repoName>/<image>:<tag>` (`docker-raw.ts`'s `imageRef`), matching exactly what
  the panel's own Docker config screen tells a user (`docker login <domain>` /
  `docker pull <domain>/<repo>/<image>:<tag>`).
- **`resolve`**: a fresh, separate work directory (fresh `DOCKER_CONFIG`/`HOME` too) with
  `crane pull --format=oci <ref> <dir> [--insecure]`. The resolved manifest blob is read back out of
  the written OCI layout's `index.json` (`manifests[0].digest`) plus `blobs/sha256/<hex>`, and its
  OWN sha256 is checked against that filename before it is trusted (`readResolvedImage`).
- **`DOCKER_CONFIG/config.json`** (rendered fresh into an isolated `HOME` per invocation, one shared
  shape for BOTH push and pull — never a machine-wide config): a Basic-transport credential (both
  `password`- and `token`-kind, same as every other protocol here) renders one
  `auths["<registryHost>"]` entry with a base64 `user:secret` `auth` value — the exact shape
  `crane auth login --password-stdin` itself writes, pinned literally by the "D2" test.
  `anonymous` writes `{"auths":{}}` directly (not through the template — see `renderDockerConfig`'s
  own comment for why a conditionally-present entry is a code decision, not a mustache section: the
  template's raw, UNRENDERED file has to stay valid JSON for `prettier`, exactly like every other
  protocol's own `*.template.json`).
- **A genuine two-hop auth model**, the first in this harness: every operation is preceded by a
  Bearer _token exchange_ (`GET /v2/token?service=repsy&scope=...`) against a `WWW-Authenticate`
  challenge `GET /v2/` returns. `docker-raw.ts`'s `dockerRequest` wraps both hops and reports which
  one answered (`hop: 'token' | 'request'`) so the loop still sees one plain HTTP status. Confirmed
  live (H6): a read-only/other-repo deploy token's own token-exchange still succeeds (`200` — the
  scope is never checked at issuance), and only the WRITE request itself is refused (`401`, at the
  OPERATION hop) — so `publish`'s raw companion probe (`rawPutManifest`, a byte-identical re-PUT of
  the exact manifest `crane push` just sent, under the same tag, with the same credential — the
  maven/npm/nuget re-PUT pattern) gets the SAME status at the SAME hop a real client would fail at.
- **The override rule is the SAME `403`** (`packageOverrideDisabled`) the shared maven pin already
  uses (`AbstractDockerProtocolTxFacade.checkRepoAllowOverride`) — no `expectByProtocol` override
  needed anywhere in `catalog.ts` for docker. **`releases`/`snapshots` are never read by the Docker
  protocol at all** (grep-confirmed), so docker is never added to
  `maven-releases-off`/`maven-snapshots-off`/`redeploy-*-off`/`snapshot-*`.
- **Fingerprint** (`ProtocolAdapter.fingerprint`/`expectNothingStored`): scoped to the ONE
  `<image>:<tag>` a scenario's own publish targets — Docker has no `tags/list`/`_catalog` route on
  Repsy (no handler exists for either) — a raw manifest GET by tag (`tagDigest`, a body hash) plus a
  `HEAD` of every blob digest the served manifest names (`'present'`/`'status:N'`). A refused
  publish's own blobs are allowed to be present (protocol-inherent: blobs go up before the manifest,
  confirmed live — see "R6" below), so `expectNothingStored` only additionally asserts the tag
  itself was never created when it did not exist before.
- **No `knownConsumeFailure`**: docker's `resolve` companion probe is a manifest GET, which never
  touches blob bytes, so there is no RPS-1205-style broken-URL failure mode to route around.
  **No `knownPublishSideEffect`** either: unlike cargo's RPS-1124, a refused manifest push never
  writes/overwrites the manifest itself (only pre-existing, already-uploaded blobs are affected, and
  that is by protocol design, not a bug — see "R6").

```bash
./run.sh test --protocol docker
```

### Why no daemon (host socket / DinD both rejected)

- **A bind-mounted host `docker.sock`** would let the HOST's own Docker daemon perform the actual
  push/pull — a host-native execution path this harness's own rule forbids (tests only ever run
  inside a runner container). It would also pollute the host's own image cache (a pull right after a
  push could be served from that cache, proving nothing about the server), cannot set
  insecure-registries for a remote plain-HTTP target without touching host-level daemon config, and
  needs docker-group socket access a `user: HOST_UID` container is not given.
- **Docker-in-Docker** needs `--privileged` and a root-started daemon (the official
  `docker:<ver>-dind`/`-dind-rootless` images both say so), a shared image store across this
  harness's parallel workers (a pull right after a push proves nothing unless the image is `rmi`'d
  first, adding synchronization this harness does not otherwise need), and ties every pinned
  manifest-media-type expectation to a DAEMON's own default image store (classic vs. containerd)
  instead of to Repsy's behaviour.
- Both are left as an explicit, opt-in follow-up ("docker-cli smoke", a later step) rather than part
  of this one. `crane` alone — daemonless, static, the same wire protocol `docker-raw.ts` probes
  raw — covers everything this step needs to pin.

### Scenario mapping onto the shared catalog

Docker needed **no changes to `catalog.ts`'s data at all**: every non-maven-restricted scenario
applies unchanged, with the SAME shared `expect` maven already pins.

| scenario (shared catalog)                                                | docker status observed      | note                                                                             |
| ------------------------------------------------------------------------ | --------------------------- | -------------------------------------------------------------------------------- |
| `no-override` (2nd publish, `allowOverride:false`)                       | `403 forbidden`             | the SAME `packageOverrideDisabled` the shared pin already expects                |
| `override` (2nd publish, `allowOverride:true`)                           | `201 ok`                    | the tag is re-pointed to the new digest                                          |
| `token-ro` publish                                                       | `401 unauthorized`          | token-endpoint issuance succeeds (`200`); the WRITE request itself fails (`401`) |
| `anonymous-public` consume                                               | `200 ok`                    | an anonymous Bearer token, issued for a public repo's `pull` scope               |
| `maven-releases-off`/`maven-snapshots-off`/`redeploy-*-off`/`snapshot-*` | n/a                         | `protocols` excludes docker — no releases/snapshots/SNAPSHOT-file concept exists |
| everything else (`password-admin`, `token-rw`, ...)                      | matches the shared `expect` | unchanged                                                                        |

`registry-rules.spec.ts` additionally pins (R1-R13, mirroring the plan's own hypothesis numbering):
the ping challenge's exact `realm`/`service`/`scope` (R1); the token-endpoint matrix — issuance is
never scope-checked, only an expired/revoked/wrong credential fails at the token hop (R2); a
read-only token's write refusal at the OPERATION hop, reads still working (R3); monolithic/chunked
blob upload, a wrong digest, and dedup (R4); manifest push validation — missing blobs, a wrong
`sha256:` reference, an unknown `Content-Type` (R5, **B4**); the override rule and an orphaned blob
after a refusal (R6); overriding a tag breaking the OLD manifest's pull-by-digest (R7, **B2**);
`HEAD` vs. `GET` by digest (R8, **B1, fixed by RPS-1215**); retagging the same digest under a second tag (R9); a
config blob missing `os`/`architecture` (R12, **B5**); a multi-arch index referencing a
digest-pushed child (R13); and that even a PUBLIC repo still needs real credentials to WRITE,
refused at the token hop with no OCI body at all (distinct from an operation-hop 401's Bearer
challenge + OCI envelope).

### H1-H14, confirmed live

Every hypothesis was probed against a running instance (`./run.sh local up`) before being pinned —
first with raw HTTP (`node`/`tsx` scripts using `docker-raw.ts`/`docker-image.ts` directly, and
`curl`), then with the real `crane` binary (both via `docker run --network host` directly against
the stack, and inside the `docker` runner container). Where a result differed from the plan's own
prediction, the actual observed behaviour is what got pinned, not the guess.

- **H1** (no `--insecure` needed for `localhost:9090`; the ping challenge shape): confirmed —
  `GET /v2/` (no auth) answers `401` with
  `WWW-Authenticate: Bearer realm="http://localhost:9090/v2/token",service="repsy",scope="repository:*:pull"`;
  `crane push`/`pull` against `localhost:9090` succeed with no `--insecure` flag at all (ggcr's own
  `pkg/name/registry.go` resolves `localhost`/loopback/RFC1918 hosts as plain HTTP automatically);
  the token GET carries `service=repsy` and ggcr's OWN scope (`repository:<repo>/<image>:push,pull`
  or `:pull`), never the challenge's constant `repository:*:pull` — confirmed with `crane -v`'s
  request trace.
- **H2** (the push wire sequence): confirmed, and MORE DETAILED than the plan's own guess —
  `crane -v push` traced live shows `GET https://.../v2/` (TLS attempt, fails) → `GET http://.../v2/`
  (`401`) → `GET .../v2/token` (`200`) → **`HEAD` the MANIFEST by tag first** (an existence check the
  plan's own H2 did not call out) → `HEAD` BOTH blobs in parallel → only the MISSING ones get
  `POST`/`PATCH`/`PUT ?digest=` → `PUT` the manifest. No `mount=`/`from=` in this harness (never a
  cross-repo `MountableLayer`, confirmed by source and by the trace).
- **H3** (a byte-identical manifest re-PUT with `allowOverride:true` succeeds, no duplicate rows):
  confirmed live (`registry-rules.spec.ts`'s R6/R9) — a re-PUT of the identical bytes under an
  existing tag with overriding allowed answers `201`, and every `ok`-expected catalog scenario's own
  publish-side raw probe (the SAME re-PUT pattern) passed across two full suite runs.
- **H4** (`GET manifests/<tag>` serves exact bytes + exact media type, no charset suffix): confirmed
  — `afterSuccessfulRoundTrip` asserts `Content-Type` equals the pushed manifest media type
  (`.split(';')[0]`, defensively) and the served body's sha256 equals the published one, on every
  successful catalog scenario.
- **H5** (`crane pull --format=oci` writes an `index.json` whose digest matches, and matching blob
  files): confirmed — `resolve`'s `readResolvedImage` verifies the resolved manifest blob's OWN
  sha256 against its filename on every consume, and `expectResolvedContent` (the shared loop) compares
  that against the published digest on every successful round trip.
- **H6** (token-ro/token-other-repo: token-hop `200`, first WRITE `401`; crane fails cleanly, no
  hang): confirmed — `registry-rules.spec.ts`'s R3, and the catalog's own `token-ro`/`token-other-repo`
  scenarios via the real client (`crane push` exit 1, no prompt, well within the timeout).
- **H7** (anonymous-public consume works with an anonymous Bearer token; anonymous publish fails at
  the token hop, no prompt): confirmed — `anonymous-public`'s `crane pull` succeeds; `anonymous-private`/
  `anonymous-public`'s own publish attempts both fail cleanly (`crane push` exit 1).
- **H8** (`no-override`: `crane push` fails, the seeded tag is unchanged, the refused push's OWN
  blobs are present): confirmed live — R6.
- **H9** (`override`: the new digest is served; the OLD digest's pullability, left open by the plan
  pending a live check): confirmed the new digest is served (R6); the OLD digest turned out to be
  **UNPULLABLE** — **B2 (RPS-1216)** (R7).
- **H10** (`HEAD` vs. `GET` by digest): confirmed live at the time — `HEAD` by digest was `404` even
  right after a `GET` by that same digest served `200` — **B1, filed as
  [RPS-1215](https://zyfera.atlassian.net/browse/RPS-1215) and since fixed** (R8). `HEAD` now
  resolves through the same `dockerFacade.getManifest(...)` GET uses, for both a tag and a digest
  reference, and mirrors GET's status/headers exactly. Fixing it surfaced one more, genuinely
  separate, previously-masked bug: `AbstractDockerProtocolTxFacade#findPlatformManifests` (building
  a manifest-list index) resolved each child by a digest-generated storage filename directly, which
  only ever found a child that had ALSO been independently re-pushed under its own digest as a
  distinct reference -- exactly what a real client's `HEAD`-then-fallback-`PUT` behavior used to do
  as a side effect of B1 itself, masking this. A second, separate ordering bug in the same area
  (`ManifestRepository#findByRepoIdAndImageIdAndDigestList`'s `.getFirst()`, after `ORDER BY
createdAt DESC`, could pick a DB-only "this manifest is also part of that multi-platform tag"
  tracking row over the original storage-backed one once a digest was shared by both) was fixed
  alongside it, in the same PR. Both are covered by `DockerManifestCheckIT`/`DockerManifestPushIT`
  and this suite's own "HD-1"/"docker-empty-base" (`tests/docker/protocol-specific.spec.ts`) and "D3"
  (`tests/docker/publish-consume.spec.ts`) tests, all green.
- **H11** (timing fits the 120s test timeout): confirmed — the whole 29-test catalog completed in
  ~7-8s total wall time across 12 parallel workers, both full runs.
- **H12** (running the whole `docker` suite twice without resetting the stack): confirmed — both
  runs passed identically (29/29, the same 4 tests showing their expected `test.fail` glyph), no
  leftover-state interference.
- **H13** (an unsupported manifest `Content-Type` → 500, after the `Image` row was already created):
  confirmed live — `text/plain` with an otherwise-valid manifest body answers a flat
  `500 {"errors":[{"code":"UNKNOWN",...,"detail":"errorOccurred"}]}`, not a `4xx` — **B4 (RPS-1110, pre-existing -- see below)**
  (R5). A config blob missing `os`/`architecture` answers the same flat `500` — **B5 (RPS-1116, pre-existing -- see below)** (R12).
- **H14** (`Seeder.cleanup()` on a docker repo with images/tags/layers succeeds; a sweep afterwards
  lists nothing): confirmed — every catalog scenario's own repo (images, tags, layers included)
  deleted cleanly in `afterEach`, and `./run.sh sweep --dry-run` found nothing left behind after a
  full suite run.

### Backend bug candidates found while reading, and confirmed live (do not fix here)

- **B1 (filed as [RPS-1215](https://zyfera.atlassian.net/browse/RPS-1215), fixed)** — `HEAD` a
  manifest by digest used to answer `404` for a manifest a `GET` of that SAME digest served fine
  (distribution spec: "HEAD MUST be identical to GET without the body"). Fixed by making
  `AbstractDockerManifestCheckProtocolMethodHandler` resolve through the same
  `dockerFacade.getManifest(...)` GET uses, instead of the old tag-only `findTagAndManifest`
  (removed, along with its one now-unreachable caller and its dead 404 branch). See "H10" above for
  the two further, previously-masked bugs this fix's own live verification surfaced and fixed in the
  same PR (a manifest-list's child-by-digest resolution, and a shared-digest row-ordering bug).
- **B2 (filed as [RPS-1216](https://zyfera.atlassian.net/browse/RPS-1216))** — Overriding a tag
  (`allowOverride: true`) makes the PREVIOUS manifest unpullable BY DIGEST, even though nothing ever
  explicitly deleted it: the tag's one `Manifest` row is reused in place (`ManifestTxService`'s
  `findOrCreateManifest`/`updateManifestProperties`), so the row's own digest simply becomes the NEW
  one. Confirmed live: `registry-rules.spec.ts`'s R7 — `GET manifests/sha256:<old digest>` is `200`
  right after the first push, then `404` right after an accepted override of the same tag.
- **B3** — _Not reproduced_ (time-boxed, per the plan). Pushing the SAME already-existing digest
  under a SECOND tag (`registry-rules.spec.ts`'s R9) was probed: both tags still resolve with a
  plain `GET`, byte-identical, right after. Since the digest (and therefore the manifest bytes) is
  identical either way, a byte-comparison alone cannot distinguish "one shared row, re-parented" from
  "each tag has its own row" — the plan's own suggested deeper repro (deleting one tag, checking
  whether the other breaks) was left unexplored, as the plan explicitly allows. Not filed; flagged
  here as an open question for whoever picks this up next, not a confirmed bug.
- **B4 (pre-existing story, [RPS-1110](https://zyfera.atlassian.net/browse/RPS-1110) — commented with
  this live evidence, not a new ticket)** — An unknown manifest `Content-Type` (anything outside the
  5 known docker/OCI types) answers a flat `500 UNKNOWN`, not a `4xx`: `saveManifest`'s `switch`
  throws a bare `IllegalArgumentException("unsupportedMediaType")`, which has no `ErrorHandler`
  mapping. The repo's `Image` row for that image name is ALSO already created by this point
  (`findOrCreateImage` runs before the `Content-Type` switch), so even the failed attempt leaves
  a row behind. Confirmed live: `registry-rules.spec.ts`'s R5.
- **B5 (pre-existing story, [RPS-1116](https://zyfera.atlassian.net/browse/RPS-1116) — commented with
  this live evidence, not a new ticket)** — A config blob missing BOTH `os` and `architecture`
  crashes the manifest push with the same flat `500`: `extractPlatform`'s `org.json`
  `getString("os")`/`getString("architecture")` throws a bare `JSONException`, also unmapped. Low
  real-world impact (every real client always sends both), but the same class of bug as B4.
  Confirmed live: `registry-rules.spec.ts`'s R12.
- **B6** — _Not a bug, a protocol-inherent design point, flagged for the coordinator's awareness
  only_ — a refused manifest push (403/404/400/500 alike) leaves any blobs it uploaded JUST BEFORE
  the refusal stored, with `Layer` rows, and no GC path except deleting the whole image/tag (the
  `DELETE /api/docker/images/{repo}/{image}` /`.../tags/{tag}` routes). This is inherent to the
  Registry v2 wire protocol itself (blobs always go up before the manifest that references them can
  even be validated) — not something the Docker protocol implementation could avoid without
  deviating from the spec. Confirmed live, `registry-rules.spec.ts`'s R6 (`expectNothingStored`
  deliberately does NOT assert the refused blobs are absent, for exactly this reason).

### Docker protocol-specific suite (step 5g, RPS-294)

`tests/docker/protocol-specific.spec.ts` ports `repsy-cloud`'s own e2e harness's docker
"multi-platform" case — with one deliberate, evidence-backed deviation from what that file actually
does, decided by the gating hypothesis (HD-1) below.

- **`repsy-cloud`'s own "multi-platform" case does NOT use `crane index append`.** It shells out to
  `docker buildx build --platform linux/amd64,linux/arm64 --push` (its `util.ts`'s
  `dockerBuildxAndPush`), fronted by `docker buildx create` with a `docker-container` driver — a
  full BuildKit daemon, needing exactly the `--privileged`/root-started-daemon/shared-image-store
  machinery this runner's own "Why no daemon" section above already rejects. Porting that literally
  was never an option here; the implementation plan's own HD-1 asked instead whether the daemonless
  `crane` binary this runner already uses everywhere else could build a genuine multi-arch index on
  its own.
- **HD-1 (gating), confirmed live**: `crane index append -m <ref1> -m <ref2> -t <indexRef>` is
  entirely daemon-free and needs nothing beyond the plain manifest-PUT wire calls this server
  already supports for a single-platform image. Read from the source FIRST:
  `AbstractDockerProtocolTxFacade.saveManifest`'s own `switch` (`repsy-protocols/docker`) routes
  `OCI_IMAGE_INDEX`/`DOCKER_MANIFEST_LIST` to `createManifestList`, a genuinely distinct,
  purpose-built code path — not the flat-500 `default -> throw new
IllegalArgumentException("unsupportedMediaType")` branch B4/RPS-1110 above pins for a truly unknown
  `Content-Type`. `createManifestList`'s own `findPlatformManifests` (same file) is what makes "push
  every child by digest FIRST" a hard SERVER rule, not just client politeness: it `getResource()`s
  each `platformManifest.getDigest()` by file name and throws `ItemNotFoundException
("resourceNotFound")` if that digest was never separately stored, so an index referencing a
  dangling child is refused outright, never silently accepted.

  Confirmed against the already-running local stack with `crane -v index append`, traced live: it
  HEADs each platform ref's manifest (already pushed by TAG in an earlier `crane push`), then
  RE-PUTs each one's exact bytes under its OWN digest as the manifest reference (`PUT
.../manifests/sha256:<digest>`, `201` both times — exactly the "a real client always pushes an
  index's children by digest first" comment `registry-rules.spec.ts`'s R13 already left for a
  hand-built RAW probe, here confirmed by a REAL client, unprompted), then `PUT`s the assembled
  index itself under the requested tag — `Content-Type: application/vnd.oci.image.index.v1+json` by
  default (crane's own default family), or `application/vnd.docker.distribution.manifest.list.v2
+json` with `--docker-empty-base` (both media types pinned by this suite's two tests) — `201`,
  `Docker-Content-Digest` echoing the index's own digest. `crane manifest <indexRef>` (no
  `--platform`) then lists both `platform` entries with their exact `architecture`/`os`, matching
  the two earlier pushes' own manifest digests exactly; `crane pull
--platform=linux/arm64 --format=oci <indexRef> <dir>` resolves to the ARM64 child specifically —
  proven by reading the PULLED config blob's own `architecture` field back out, not merely trusting
  the index's platform label.

- No new backend bug was found while building this suite. R13 (`registry-rules.spec.ts`) had
  already pinned the raw-HTTP shape of an index push; RPS-946 ("Docker image manifest lookup by
  digest fails for per-platform manifests of multi-platform tags", filed independently, status
  Done) turned out to be about the _different_ panel UI endpoint
  (`/api/docker/images/.../manifests/{reference}`), not the registry protocol path `crane`/this
  suite exercises — its own description says the registry path "already resolves such digests"
  (`ManifestService.findManifestByRepoIdAndImageNameAndDigest`), which is exactly what this suite
  confirms live, end to end, with a real client.

```bash
./run.sh test --protocol docker -b   # -b the first time: builds the docker runner image
```

## Helm runner

Repsy implements **two independent wire protocols** for Helm on the same protocol port: OCI
distribution-spec (`GET|HEAD|PUT /v2/<repo>/<chart>/manifests|blobs/...`, what `helm push`/`helm
pull oci://` speak) and the classic ChartMuseum protocol (`GET /<repo>/index.yaml`, `GET
/<repo>/charts/<file>.tgz`, `POST /api/<repo>/charts` / `POST /<repo>/api/charts`, `DELETE
/<repo>/api/charts/<name>/<version>`, what `helm repo add`/`helm pull --repo`/the `cm-push` plugin
speak) — different path parsers, different handler classes, different override-check placement,
different error envelopes (an OCI JSON envelope on `/v2/...`, the panel envelope or a bare body
elsewhere). This harness therefore runs the **shared catalog twice**, as two adapters sharing one
runner/project and one `RepoType.HELM` repo:

- **`helmAdapter`** (`protocol: 'helm'`, `clients/helm.ts`) — real `helm push`/`helm pull
oci://...`, both flags `--plain-http` (derived unconditionally from `REPSY_REPO_BASE_URL`'s
  scheme, confirmed live H3: unlike `crane`, Helm has no localhost auto-detection).
- **`helmClassicAdapter`** (`protocol: 'helm-classic'`, `clients/helm-classic.ts`) — real `helm
cm-push` (the chartmuseum/helm-push plugin v0.11.1, installed at build time into a shared,
  read-only `HELM_PLUGINS` dir, `runners/helm.Dockerfile`) and `helm pull --repo`.

The only harness-wiring change either needed: `fixtures.ts`'s `REPO_TYPE_BY_PROTOCOL` maps
`'helm-classic'` to the SAME `RepoType.HELM` as `'helm'` (the seeder derives repo names from the
`RepoType`, so both adapters' repos are named `e2e-<runid>-helm-<n>`, never colliding since every
test gets its own `Seeder`/run id), and `catalog.ts`'s `no-override` scenario gets
`expectByProtocol: { helm: { publish: 'conflict' }, 'helm-classic': { publish: 'conflict' } }` — a
real `409` in both modes, confirmed live (like nuget). Every other scenario's shared, maven-pinned
`expect` already matches both Helm modes unchanged (single-hop Basic auth in both, no
releases/snapshots concept in either — `protocols` excludes helm/helm-classic from
`maven-releases-off`/`maven-snapshots-off`/`redeploy-*-off`/`snapshot-*`, grep-confirmed no Helm
code reads either repo setting).

```bash
./run.sh test --protocol helm -b   # -b the first time: builds the helm runner image
```

### Chart fixture

`clients/helm-chart.ts`'s `buildChart` hand-assembles a chart `.tgz` (one `<name>/` directory:
`Chart.yaml` rendered from a mustache template, `values.yaml`, `e2e-marker.txt` — a fresh random
marker per publish), packed with `docker-image.ts`'s own `buildTar` (a minimal ustar builder, mtime
0, no pax headers) and gzipped — deliberately never `helm package`, the same "own the exact bytes"
reasoning as every other hand-built-artifact adapter here. Confirmed live (H17): Helm 4.3.0's
loader accepts this exact shape for both `helm push` and `cm-push`.

### Two corrections found only by running the real client (not in the original plan)

Both surfaced as every "ok"-expected scenario failing outright the first time the real suite ran
inside the container, and were root-caused with `helm --debug` and hand-built minimal repros
(neither is a Repsy backend bug — both are this adapter's own client-usage mistakes, fixed in
`helm.ts`/`helm-classic.ts` before any test was pinned against them):

- **`helm push` takes a REPO-level ref, not a chart-level one.** `helm push <tgz> oci://<host>/
<repo>` is correct; `oci://<host>/<repo>/<chart>` (this adapter's first attempt, by analogy with
  `helm pull`, which DOES need the chart segment) makes Helm append the chart's OWN name from its
  `Chart.yaml` a SECOND time (`pkg/pusher/ocipusher.go`: `ref = path.Join(<host>/<repo>,
meta.Name) + ":" + meta.Version`), so the blob-upload/manifest requests land on a doubled path
  (`/v2/<repo>/<chart>/<chart>/blobs/...`) that 404s with `NAME_UNKNOWN`/`unknownPath` — confirmed
  live, reproduced with `helm --debug` and fixed by using `ociRepoRef(repoName)` (no chart
  segment) for `helm push`, keeping `ociChartRef(repoName, chart)` (WITH the chart segment) for
  `helm pull`, which has no local chart metadata to derive a name from.
- **`helm pull --destination <dir>` does not create `<dir>` itself.** Unlike `crane pull
--format=oci` (which does), a `helm pull` into a destination directory that does not exist yet
  downloads successfully (prints `Pulled:`/`Digest:`) but then fails to persist the file
  (`Error: open .../<file>.tgz<random>: no such file or directory`), exit 1 — confirmed live. Both
  `resolve()` functions now `fs.mkdir(pulledDir, { recursive: true })` before calling `helm pull`.

### `helm cm-push` repackages the chart -- confirmed live, a genuine plan deviation

The plan assumed `cm-push`, like every other real client here, sends a file's bytes VERBATIM.
Confirmed live that it does **not**: `cmd/helm-cm-push/main.go`'s `push()` always calls
`helm.GetChartByName` (`loader.Load`, for a `.tgz` OR a directory) and then
`helm.CreateChartPackage` (`chartutil.Save`) to build a FRESH package in a temp dir, which is what
actually gets POSTed — so the classic-stored bytes for a `cm-push` are a repackaged chart, never
byte-identical to the file this adapter built (same logical content, different tar/gzip layout).
This does not break the adapter's design (the plan's own byte-identical-re-POST pattern already
overwrites whatever `cm-push` stored with this adapter's own known bytes for every `ok` outcome),
but it does mean `seedPublish()` cannot trust `sha256(tgzBytes)` for what actually landed in
storage — it reads back an admin download right after a successful `cm-push` and hashes THAT
instead (`helm-classic.ts`'s file header has the full reasoning). `helm push`'s OCI chart layer has
no such issue: `pkg/registry/client.go` reads the file's bytes directly, confirmed live (the served
layer digest always equals `sha256(tgzBytes)`).

### Scenario mapping onto the shared catalog

| scenario (shared catalog)                                                | helm (OCI)                  | helm-classic                | note                                                                                                                                                                                                |
| ------------------------------------------------------------------------ | --------------------------- | --------------------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `no-override` (2nd publish, `allowOverride:false`)                       | `409 conflict`              | `409 conflict`              | `ItemAlreadyExistException("chartAlreadyExists")` in each mode's own override check, confirmed live (OCI: `DENIED`/`chartAlreadyExists` OCI body; classic: the panel `chartAlreadyExists` envelope) |
| `override` (2nd publish, `allowOverride:true`)                           | `201 ok`                    | `201 ok`                    | OCI: the manifest row is updated in place (chart-version row is NOT, see B-H2); classic: `update` overwrites the stored file AND refreshes the version row's digest                                 |
| `token-ro` publish                                                       | `401 unauthorized`          | `401 unauthorized`          | single-hop Basic in both modes, no token exchange to succeed at                                                                                                                                     |
| `anonymous-public` consume                                               | `200 ok`                    | `200 ok`                    | public + READ skips `HelmHeaderPreProcessor` in both modes                                                                                                                                          |
| `maven-releases-off`/`maven-snapshots-off`/`redeploy-*-off`/`snapshot-*` | n/a                         | n/a                         | `protocols` excludes helm/helm-classic — no releases/snapshots concept in either mode                                                                                                               |
| everything else                                                          | matches the shared `expect` | matches the shared `expect` | unchanged                                                                                                                                                                                           |

`registry-rules.spec.ts` additionally pins (R1-R14, mirroring the plan's own numbering, covering
BOTH modes): the `/v2/` ping is Docker's own, not Helm's (R1); the OCI auth matrix — single-hop
Basic, no token exchange (R2); blob upload flows — monolithic, chunked, wrong digest, dedup (R3);
OCI manifest push validation — missing blob, malformed JSON, empty layers, a MISSING (not merely
empty) `Content-Type` (R4, **B-H7**); the OCI override rule (R5); `GET tags/list` has no handler at
all (R8, **B-H3**); classic upload rules — missing `chart` part (plain text, no envelope),
Chart.yaml validation, uppercase names, both classic routes accepting the same request (R11);
classic read auth — bare 401 on a private repo, 200 with credentials, anonymous 200 on a public one
(R12); `index.yaml`'s shape (R13, **B-H8**); and `DELETE` removing a chart from both the classic
index and, for an OCI-pushed chart, its manifest too (R14).

### H1-H18, confirmed live

Every hypothesis was probed against a running instance (`./run.sh local up`) before any adapter
code was written — first with the real `helm`/`helm cm-push` binaries run directly against the
stack (both from the host and inside the `helm` runner container), then with raw HTTP
(`curl`/`tsx` scripts using `helm-raw.ts` directly). **H1, H4, H3, H9 gated the whole design and
were probed first, as instructed.**

- **H1** (does `helm registry login` fail with a WRONG password?): **the plan's own predicted
  answer was right, confirmed live at the time** — it did NOT fail. `helm registry login
localhost:9090 --plain-http -u <user> --password-stdin` with an intentionally wrong secret used to
  print "Login Succeeded", exit 0, and write the (wrong) credential into `HELM_REGISTRY_CONFIG`
  regardless — see **B-H4** below, now fixed by RPS-1220. A wrong password now genuinely fails the
  login (and the push it fronts).
- **H3** (`--plain-http` required for `localhost:9090`): confirmed — without it, `helm registry
login`/`push`/`pull` all fail with `http: server gave HTTP response to HTTPS client`.
- **H4** (`helm pull oci://... --version <exact>` succeeds against Repsy): confirmed, the gating
  result for the whole OCI consume design — a real `helm push` then `helm pull ... --version
<exact>` round-trips with byte-identical content (sha256 verified).
- **H9** (helm-push v0.11.1 loads under Helm 4.3.0 and `cm-push` works): confirmed, WITH ONE
  ADDITION the plan did not anticipate — `helm plugin install <url> --version v0.11.1` alone
  refuses with `Error: plugin source does not support verification. Use --verify=false to skip
verification` (Helm 4 verifies a plugin's signature by default, and this plugin publishes none);
  `--verify=false` (added to the install command, `runners/helm.Dockerfile`) makes the install
  (and every subsequent `cm-push`) work. See "`helm cm-push` repackages the chart" above for the
  one behavioural surprise found once it was actually exercised end to end.
- **H2** (the push wire sequence): confirmed via `helm --debug` — `HEAD` the manifest by tag,
  `HEAD` each blob, only missing blobs get `POST`/`PATCH`/`PUT ?digest=` (concurrently, both blobs
  at once — not sequential, a detail the plan's guess did not call out), then `PUT` the manifest.
- **H5** (`HELM_REGISTRY_CONFIG` shape after `helm registry login`): confirmed —
  `{"auths":{"localhost:9090":{"auth":"<base64 user:secret>"}}}`, the exact shape
  `renderHelmRegistryConfig` renders by hand (pinned literally by "HL1").
- **H6** (every negative credential fails the real client promptly, no hang): confirmed across the
  whole catalog, both modes, well within the 120s test timeout.
- **H7** (an admin re-PUT of the served manifest under the same tag, `allowOverride:true`, answers
  201 and `helm pull` still works afterwards): confirmed, WITH THE CONFIG-BLOB CAVEAT found live —
  see the next paragraph.
- **H8** (served manifest `Content-Type` has no `;charset=` suffix, `Docker-Content-Digest` equals
  `sha256` of the body): confirmed, asserted on every successful OCI round trip
  (`afterSuccessfulRoundTrip`).
- **H10** (`helm pull --repo` works on a private repo with credentials, anonymous on public):
  confirmed, exercised by every catalog scenario via `helmClassicAdapter`.
- **H11 (B-H1)** (`helm pull --repo` of an OCI-pushed chart → 404): confirmed live, cleanly (a
  chart name that never touched the classic route) — "HL4".
- **H12 (B-H2)** (OCI override leaves `index.yaml`'s digest at the old layer): confirmed live —
  "HL5".
- **H13 (B-H3)** (`GET tags/list` → 404; `helm pull oci://` without `--version` fails): confirmed
  live — "HL2", "R8".
- **H14** (timing fits 120s per test; the whole suite twice without a stack reset passes
  identically): confirmed — 43/43 both runs, ~8s wall time each across 12 workers.
- **H15** (`Seeder.cleanup()` deletes a Helm repo with classic charts, OCI blobs and manifests;
  `./run.sh sweep --dry-run` finds nothing): confirmed.
- **H16** (`helm-classic` as a protocol key works end to end through `fixtures.ts`/
  `registerPublishConsumeLoop`): confirmed — unique titles, `RepoType.HELM` shared with `helm`, no
  name collisions.
- **H17** (Helm 4's loader accepts the hand-built ustar tgz for both `helm push` and `cm-push`):
  confirmed live, reused `docker-image.ts`'s own `buildTar`.
- **H18** (classic refusals leave storage/index byte-identical): confirmed — `expectNothingStored`
  held for every refused classic-mode scenario across two full suite runs.

A discovery beyond the H-numbered list, found while making H7 work: an OCI manifest push handler
never reads the CONFIG blob's own bytes (confirmed live — a manifest whose config digest names a
blob that was NEVER uploaded still gets accepted, `201`), but a real `helm pull`'s own client-side
fetch (`oras`'s `Copy`) DOES resolve every blob a served manifest names, config included, and fails
("not found") if one is missing. `publish()`'s re-PUT therefore also raw-uploads a real config blob
(`helm.ts`'s own `buildConfigBytes`) before referencing it, so every "ok"-expected scenario's own
`resolve()` (a real `helm pull`) keeps working — otherwise this would have looked like a server
bug on every successful scenario, when it is actually this adapter needing to satisfy the CLIENT's
own validation, which is stricter than the server's.

### Helm protocol-specific suite (step 5h, RPS-294): already fully covered, nothing added

Step 5h's own plan included a Helm alias-flow sub-scope (`helm repo add <alias> <repoUrl>` + `helm
repo update` + `helm search repo <alias>/<chart>` + `helm pull <alias>/<chart> --version <v>`).
Checked first, as instructed: `tests/helm/classic-publish-consume.spec.ts`'s existing "C1" test
(added in this Helm runner step, "Scenario mapping onto the shared catalog" above) already drives
this exact flow end to end, real `helm` binary, real `cm-push` by repo alias — so this sub-scope was
skipped as already-covered rather than duplicated; see the PyPI protocol-specific suite section
below for the step 5h work that WAS added (PyPI sdist support).

### Backend bug candidates found while reading and confirmed live (do not fix here)

- **B-H1 (filed as [RPS-1217](https://zyfera.atlassian.net/browse/RPS-1217))** — `index.yaml`
  (`generateIndex`) lists every chart version a repo has, INCLUDING ones that only ever went
  through the OCI route, at `charts/<name>-<version>.tgz` — but the classic download handler
  (`getChart`) only ever reads the classic storage path, which an OCI-only publish never wrote, so
  `GET charts/<name>-<version>.tgz` (and therefore `helm repo add` + `helm
search`/`install`/`pull <repo>/<chart>`, or a raw `helm pull --repo`) 404s (`chartNotFound`) for a
  chart that was only ever pushed via `helm push oci://`. Confirmed live, cleanly (a chart name that
  never touched the classic route): `tests/helm/publish-consume.spec.ts`'s "HL4".
- **B-H2 (filed as [RPS-1218](https://zyfera.atlassian.net/browse/RPS-1218); related to, but
  distinct from, the pre-existing RPS-1111 — see that ticket's description for how)** — An accepted
  OCI override (`allowOverride:true`, different chart bytes, same name:version) updates only the
  manifest row in place; the chart VERSION row (and therefore `index.yaml`'s `digest` field, and the
  panel's own version-detail DTO) stays at the OLD layer digest. Confirmed live: "HL5".
- **B-H3 (filed as [RPS-1219](https://zyfera.atlassian.net/browse/RPS-1219))** — There is no
  `GET /v2/<repo>/<name>/tags/list` handler at all (`404` with OCI code `NAME_UNKNOWN`, msgId
  `unknownPath`), although `HelmFacade.listTags`/`HelmOciTagListDto` exist (used only by the panel's
  own `GET /api/helm/charts/{repo}/{name}/tags`). Helm's own `ValidateReference` calls `Tags(...)`
  whenever `--version` is empty or a semver CONSTRAINT, so a real `helm pull`/`install`/`show
oci://.../<chart>` without an EXACT version fails outright against Repsy. Confirmed live: "HL2",
  "R8".
- **B-H4 (filed as [RPS-1220](https://zyfera.atlassian.net/browse/RPS-1220), fixed)** — Lived in the
  DOCKER provider's token endpoint, surfaced through Helm's shared `/v2/` ping: `helm registry
login` used to succeed with a WRONG password. Docker's `/v2/token` answered the ping's own
  OAuth2-form POST (no `Authorization` header at all — oras-go's `ForceAttemptOAuth2` path,
  requesting the wildcard `repository:*:pull` scope) with an ANONYMOUS token, `200`, before any
  credential was ever checked; `helm registry login` treated that `200` as success. Fixed by adding
  a `grant_type=password` branch (gated on `authHeader == null && formCredentials != null`, so the
  existing anonymous/Basic-header paths are untouched) that authenticates the form's
  username/password through the same check the Basic path uses. Confirmed live: "HL1", now genuinely
  failing a wrong-password login (and the push it fronts).
- **B-H5** (observation, resolved as a byproduct of RPS-1215) — used to note that a manifest was
  only addressable by the exact reference it was pushed under (`GET`/`HEAD` by digest of a
  tag-pushed manifest both `404`, affecting any future `helm pull oci://...@sha256:<digest>`).
  RPS-1215's fix (Docker's shared `AbstractDockerManifestCheckProtocolMethodHandler`, which Helm
  OCI rides) makes `HEAD`/`GET` by digest work for a tag-pushed manifest too — not independently
  re-verified with a dedicated Helm-side test here (no real client flow this step drives needs it),
  but the underlying mechanism is the same one `tests/docker/publish-consume.spec.ts`'s "D3" now
  confirms green.
- **B-H6** — Not exercised: a repro needs a real `helm push` to emit a `.prov` layer BEFORE the
  chart layer in one manifest, which was not confirmed to be producible with the harness's own
  chart fixture (no `.prov` file is ever generated here) — left as an open question, not a
  confirmed bug, per the plan's own "only write this test if you confirm..." guidance.
- **B-H7 (pre-existing story, [RPS-1110](https://zyfera.atlassian.net/browse/RPS-1110) — commented
  with this live evidence, not a new ticket)** — An OCI manifest push with NO `Content-Type` header
  at all answers a bodyless `400` — no OCI envelope despite RPS-1039 (`OciErrorBodyAdvice`); RPS-1110
  already lists exactly this handler (`AbstractHelmOciManifestPushProtocolMethodHandler`, "a bare 400
  when ... a header is missing") among its scope. Likewise the classic push handler's `Missing
'chart' part` `400` is plain text, not the panel's own JSON envelope — that one is NOT in
  RPS-1110's scope (the classic route was never meant to carry the OCI envelope, so this is by
  design, not a bug). Confirmed live: `registry-rules.spec.ts`'s R4/R11. (An EMPTY, as opposed to
  ABSENT, `Content-Type` header does not trip this — confirmed live while writing R4: only a truly
  missing header does.)
- **B-H8** (observation) — `index.yaml`'s `digest` field carries a `sha256:` prefix, whereas a real
  `helm repo index` emits bare hex. Low impact: neither `helm repo add`/`update`/`pull`/`install`
  verify it against anything. Confirmed live: `registry-rules.spec.ts`'s R13.

## PyPI runner

`runners/pypi.Dockerfile` copies a pinned CPython (`python:3.14.7-slim-bookworm`'s
`/usr/local/bin/python3.14` + `/usr/local/lib/python3.14` + `libpython3.14.so.1.0`) in from that
official image rather than installing Debian bookworm's own `python3` package (3.11, not pinned to
this story) — the same "copy the toolchain, not the whole image" approach as
cargo.Dockerfile's Rust toolchain / nuget.Dockerfile's .NET SDK. The extra runtime `.so` deps CPython
needs beyond what `node:24-bookworm-slim` already carries were found by `ldd`-ing every stdlib
extension module under `lib-dynload/` and cross-checking against a bare `node:24-bookworm-slim`
container's own `dpkg -l`/`ldconfig -p` (not copied from a generic "python runtime-deps" list): only
`ca-certificates libssl3 libsqlite3-0 libreadline8 libncursesw6 libgdbm6` were missing
(`libbz2`/`libdb5.3`/`libffi8`/`liblzma5`/`libuuid1`/`zlib1g`/`libtinfo6` are already part of the base
image's own dependency closure) — confirmed live, the image built and `python3 -c "import ssl,
hashlib, zlib, bz2, lzma, sqlite3, ctypes, uuid"` succeeded on the FIRST attempt with exactly that
list (H24). `pip==26.2.1`/`twine==7.0.0` are installed once at build time into that same interpreter.
`clients/pypi.ts` builds a wheel directly with `fflate` (`pypi-raw.ts`'s `buildWheel` — **no `python
-m build`/`setuptools`/`wheel`**: a zip with a package dir + marker file, a `.dist-info/METADATA`
carrying only the headers twine 7's `packaging.metadata.parse_email` recognizes, a minimal `WHEEL`,
and a `RECORD` covering every other entry) and runs the real `twine`/`pip` binaries, always as
`python3 -m twine`/`python3 -m pip`, never the console scripts:

- **`publish`**: `python3 -m twine upload --non-interactive --disable-progress-bar --repository-url
<repoBaseUrl>/<repo>/ <wheel>` against the pre-built wheel — no packaging step to precede it, the
  bytes are already on disk. `TWINE_USERNAME`/`TWINE_PASSWORD` carry the credential's
  username/secret for both `token`- and `password`-kind credentials (a token's username is ignored
  server-side either way); `anonymous` leaves both env vars UNSET entirely, which makes twine's own
  `--non-interactive` preflight fail client-side (`NonInteractive: Credential not found for
username.`, exit 1) BEFORE any HTTP request — confirmed live, H4.
- **`resolve`**: `python3 -m pip download --no-deps --only-binary=:all: --no-cache-dir --dest <dir>
<name>==<version>`, with `PIP_INDEX_URL` carrying URL-embedded, percent-encoded Basic credentials
  (`PIP_CONFIG_FILE=/dev/null` so no config file anywhere is ever read). `pip` leaves the wheel under
  `--dest` with its exact original filename (never unpacked), so `AdapterResult.contentSha256` is the
  sha256 of that WHOLE file, exactly like nuget's `.nupkg`/cargo's `.crate`.
- **A real override rule, with the SAME 403 maven already pins**: `AbstractPypiProtocolFacade
.checkOverridePermission` refuses `!allowOverride && isPackageFileExist(...)` with `403
fileAlreadyExists` — matching the shared catalog's `forbidden` pin byte-for-byte, no
  `expectByProtocol` override needed (confirmed live, like docker's/helm's own `no-override`
  bullets). The rule is per FILENAME, not per declared version (see the P3 bug candidate below).
- **The publish-side raw probe IS a byte-identical re-POST** of the exact wheel the client just
  uploaded (the maven/npm/nuget pattern, unlike cargo's prerelease-sibling workaround): every
  `ok`-expected scenario runs with `allowOverride: true` (the fixture default), so the re-POST is an
  accepted, identical replacement; `no-override` gets the same `403` the client got.
- **The consume-side raw probe** is a project-page `GET` (`/<repo>/simple/<name>/`), mirroring
  nuget's flat-version-list-GET / cargo's sparse-index-GET reasoning: every consume expectation in
  the catalog is an authn/authz outcome, and this GET never touches the (possibly P3/P4/P5-affected)
  archive bytes.
- **Fingerprint** (`ProtocolAdapter.fingerprint`/`expectNothingStored`): scoped to the one package a
  scenario's publish targets, like nuget's/cargo's — the project-page body hash (`undefined` when the
  package does not exist at all) plus every listed file's downloaded content hash.
- **No `knownConsumeFailure`/`knownPublishSideEffect`**: none of the confirmed bug candidates (P1-P6
  below) are reachable through the catalog loop's own scenarios (real `twine`/`pip` always send a
  matching filename/version/digest), so no routing-around hook is needed — checked live, not assumed.

```bash
./run.sh test --protocol pypi
```

### Scenario mapping onto the shared catalog

Every catalog scenario that is not maven/nuget-restricted applies to pypi unchanged, with the SAME
`unauthorized`/`ok`/`forbidden` buckets maven already pins — `PypiAuthPreProcessor` throws the same
flat `401` + `WWW-Authenticate: Basic` challenge for every authn/authz failure (no separate
"forbidden" outcome), and `no-override`'s `403 fileAlreadyExists` matches the shared pin exactly. pypi
is never added to `maven-releases-off`/`maven-snapshots-off`/`redeploy-*-off`/`snapshot-*`: the
`releases`/`snapshots` repo settings are never read by any PyPI code at all (grep-confirmed across
both `repsy-protocols/pypi` and `repsy-backend/.../protocols/pypi`, and confirmed live — H23:
`.dev0`/`a1`/`.post1` versions all publish and serve fine regardless of either switch). No data
changes were needed in `catalog.ts` beyond the file-header/inline-comment bullets documenting this
(same as docker's/helm's own "no data change needed" bullets) — verified live, not assumed.

`registry-rules.spec.ts` additionally pins: a read-only token's flat 401 (not 403) with the same
token still able to read (H5); a non-multipart POST answering 404 `unknownPath` and a multipart POST
missing the `content` part answering a bodyless 400 (H11); the no-trailing-slash upload URL spelling
being accepted (H12); invalid archive filenames refused with 400; the 307 redirect to a normalized,
trailing-slashed project page (H14); `HEAD` mirroring `GET`'s status instead of answering 200
unconditionally (RPS-1226, fixed); a pre-release/dev/post version publishing fine
(H23; `releases`/`snapshots` cannot be switched off on PyPI since RPS-1210); and an unknown package/file 404ing. It also pins backend
bugs found while reading the server source and confirmed live (see below), most via `test.fail()`.

### H1-H24, confirmed live

Every hypothesis below was probed against a running instance (`./run.sh local up`) before being
pinned — first with raw `curl`/Node `fetch` probes and a throwaway `python:3.14.7-slim-bookworm`
container (`docker run --network host`), then with the real `twine`/`pip` binaries inside the built
`pypi` runner container. H1-H4 gated the whole design and were probed FIRST, before any adapter code
was written.

- **H1** (twine accepts the real, unknown-field-carrying form; Repsy accepts it): confirmed live, on
  the FIRST attempt — a real `twine upload` of a hand-built wheel against a fresh repo answered `200`,
  twine exit `0`, even with `:action`/`protocol_version`/etc. in the form (Jackson 3's
  `FAIL_ON_UNKNOWN_PROPERTIES` defaults to `false`, as the plan predicted from source).
- **H2** (`pip download` with URL-embedded Basic credentials fetches the wheel from a PRIVATE repo,
  byte-identical): confirmed live — the downloaded file's sha256 matched the uploaded one exactly.
- **H3** (twine streams bytes verbatim): confirmed live — an admin raw download right after upload
  (and after a real `pip download`) both sha256-match the original hand-built bytes exactly.
- **H4** (anonymous fails client-side before any request; pip exits promptly against a private repo
  with no creds): confirmed live — `twine upload` with no `TWINE_USERNAME`/`PASSWORD` and
  `--non-interactive` prints `NonInteractive: Credential not found for username.` and exits 1; `pip
download` with no creds against a private repo exits 1 promptly (`ERROR: Could not find a version
that satisfies the requirement ...`), no hang, no retry storm.
- **H5** (`token-ro` publish is a flat 401, not 403; the same token can still read): confirmed live —
  `401` + `WWW-Authenticate: Basic realm="Repsy Managed Repository"` + the panel's `unAuthorized`
  envelope on publish; `200` on the project-page read with the identical token.
- **H6** (`no-override`: real twine gets 403, raw re-POST gets the same, fingerprint unchanged):
  confirmed live.
- **H7** (`override`: 200, stored bytes AND sidecar replaced; a follow-up `pip download` returns the
  NEW bytes): confirmed live.
- **H8** (the served `href` names exactly ONE repo segment, the RPS-1205 analogue does NOT
  reproduce): confirmed live — `http://localhost:9090/<repo>/<name>/-/<file>`, never a doubled or
  cloud-layout segment.
- **H9** (`data-requires-python=">=3.9"` renders HTML-escaped and pip accepts it): confirmed live —
  `&gt;=3.9` in the served HTML, unescaped back to `>=3.9` by `parseSimplePage`, and every real `pip
download` in the catalog loop succeeds against pages carrying it.
- **H10** (running the suite twice without resetting the stack, both green): confirmed — two
  consecutive `./run.sh test --protocol pypi` runs both passed 29/29 with no stack reset in between.
- **H11** (a non-multipart POST is 404 `unknownPath`, not 400; a multipart POST missing `content` is
  a bodyless 400): confirmed live, exactly as the plan predicted from source (no handler at all
  matches a non-multipart POST, so the router's own catch-all 404 fires, not the upload handler's).
- **H12** (`POST /<repo>` with no trailing slash accepted like `POST /<repo>/`; `POST /<repo>/simple`
  404s `unknownPath`): confirmed live — see RPS-1222 below for why this matters.
- **H13** (the root `/simple/` index has malformed hrefs and no `text/html` content type): confirmed,
  and with an EXTRA quirk beyond the plan's own prediction — the response's `Content-Type` was
  `application/json`, not merely "unset"/defaulted, despite the body being HTML (RPS-1221, fixed).
- **H14** (a non-normalized/no-trailing-slash name 307-redirects to the normalized page): confirmed
  live, both via a raw probe and inside a real-client dedicated test (`publish-consume.spec.ts`'s
  H21 test).
- **H15** (a version/filename mismatch bypasses `allowOverride: false`): confirmed live — see
  RPS-1223.
- **H16** (`badVersionString` used to leave an orphaned, downloadable file+sidecar): confirmed live,
  then fixed by RPS-1124/#508 — see P4.
- **H17** (a missing `sha256_digest` is a 500; a wrong one is served as-is): confirmed live — see
  RPS-1224/RPS-1225.
- **H18** (`HEAD` of a never-published path was 200): confirmed live, then fixed — see RPS-1226.
- **H19** (a real `pip install --no-index --find-links ... --target ...` succeeds and the marker
  survives): confirmed live — `publish-consume.spec.ts`'s dedicated test; the RECORD/WHEEL metadata
  this harness writes is well-formed enough for pip's own installer, not merely for twine's
  `parse_email`.
- **H20** (mixed wheel+sdist upload ordering): NOT built — dropped per the plan's own "if
  time-boxed, drop the sdist entirely" escape hatch; only a wheel fixture exists in this step, so
  `buildSdist`/`src/packages/pypi/` were never created. `twine`'s own `_find_dists`
  wheel-before-sdist ordering is documented in `pypi-raw.ts`'s file header from source, but not
  independently exercised by a dedicated test.
- **H21** (mixed-case/dotted package name round trip): confirmed live — `publish-consume.spec.ts`'s
  dedicated test: a real `twine upload` under `E2E.<runid>.MixedCase`, a raw non-normalized `GET`
  307-redirecting to the normalized page, and a real `pip download` of the same raw name resolving to
  the exact published bytes (pip canonicalizes the requirement name itself before requesting).
- **H22** (negative scenarios in parallel stay under the auth throttle): confirmed — two full
  parallel (12-worker) catalog runs both passed with no 429 observed.
- **H23** (no releases/snapshots rule): confirmed live — `registry-rules.spec.ts`'s dedicated test:
  `1.0.0`/`1.0.0a1`/`1.0.0.post1`/`1.0.0.dev0` all publish and serve fine; since RPS-1210
  `releases`/`snapshots` cannot be switched off on a PyPI repo at all (the settings PUT answers 400
  `releasesSnapshotsUnsupported`, pinned in `skeleton/repo-settings.spec.ts`).
- **H24** (the runner image's stdlib import check passes with the derived apt list): confirmed live
  on the FIRST build attempt — see this section's opening paragraph for the exact package list and
  how it was derived (`ldd` against a live container, not a generic runtime-deps list).

### Backend bug candidates found while reading and confirmed live (do not fix here)

- **RPS-1221 (fixed)** — `packages.ftl` (the root `/simple/` index, rendered by
  `PypiSimpleHandlerPreProcessor`) used to hard-code cloud-layout hrefs,
  `/pypi/<repoName>/simple/<name>/`, that 404 on Repsy OS's single-tenant layout (the real, working
  path is `/<repoName>/simple/<name>/` — no `/pypi/` prefix at all here). A second, narrower quirk
  found only by probing live (not predicted by the plan): the response's own `Content-Type` was
  `application/json`, not `text/html`, despite the body being this same HTML. Low impact — `pip
install`/`download` never fetch the root page, only `/simple/<project>/` — but a real PEP 503 spec
  violation. Fixed: `PypiPackageServiceImpl.getPackageList` now passes a `repoUri` built the same way
  `PypiStorageService.buildRepoUri` does (`RequestBaseUrlUtils.resolveBaseUrl()` + the repo name),
  `packages.ftl` renders `${repoUri}/simple/${package.getNormalizedName()}/`, and
  `PypiSimpleHandlerPreProcessor` now sets `Content-Type: text/html` explicitly. Confirmed live:
  `registry-rules.spec.ts`'s root-index test.
- **RPS-1222** — The panel's own PyPI config screen (`pypi-config.component.ts`) tells users to
  set `.pypirc`'s `repository=${baseUrl}/${repoName}/simple`, but the upload handler only matches the
  repo ROOT (`POST /<repo>/` or `/<repo>`) — a `twine upload -r <that source>` built from the panel's
  OWN instructions 404s with `unknownPath`. Confirmed live: `registry-rules.spec.ts`'s
  no-trailing-slash/`P2` test.
- **RPS-1223** — `checkOverridePermission`/`isPackageFileExist` compares the FORM `version`
  field against the version RE-EXTRACTED from the archive FILENAME (`isFileBelongsRelease`), not the
  filename directly — a form `version` that does not match the filename's own encoded version makes
  an existing file overwritable even under `allowOverride: false`, bypassing the rule entirely.
  Confirmed live: `registry-rules.spec.ts`'s override test (same filename, mismatched declared
  version, `allowOverride: false`, `200` instead of the expected `403`).
- **P4** (fixed, RPS-1124/#508): `AbstractPypiStorageService.writePackageArchive` (the archive file
  AND its `.sha256` sidecar) used to run BEFORE `PypiPackageServiceImpl.addOrUpdateRelease`, where
  `ReleaseVersion.of(form.version)` can still throw `badVersionString`, so a validation failure
  after the storage write left an orphaned, directly-downloadable archive+sidecar with no DB row.
  The release rows are now written first, in one transaction, and the archive only afterwards:
  `registry-rules.spec.ts`'s badVersionString test asserts the refused upload is `404` for real.
- **RPS-1224 / RPS-1225** — The `.sha256` sidecar is the client-sent `sha256_digest` form field stored
  VERBATIM, never recomputed or verified against the actual uploaded bytes
  (`uploadForm.getSha256_digest().getBytes()`). A MISSING digest crashes with an unhandled NPE
  (`500`, confirmed live — RPS-1224); a WRONG digest is silently served to every consumer as if
  correct (confirmed live — RPS-1225). Confirmed: `registry-rules.spec.ts`'s two digest tests.
- **RPS-1226 (fixed)** — `HEAD` on ANY path under a pypi repo used to answer `200` unconditionally;
  existence was never checked. Fixed, mirroring the Ruby analogue (RPS-1237):
  `AbstractPypiHeadProtocolMethodHandler` now dispatches per path kind onto existence-only lookups
  (`PypiProtocolFacade.packageExists`/`archiveFileExists`, never `getPackageList`/
  `downloadArchiveFile`) — `/simple/` still always `200` (the path parser already guarantees the repo
  exists), `/simple/<project>/` `200`/`404` on the package's existence (mirroring `GET`'s `307`
  redirect first for a non-normalized name), and `/<project>/-/<file>` `200`/`404` on the archive
  file's existence. Confirmed live: `registry-rules.spec.ts`'s HEAD test.
- **P7** (observation, no test) — `PypiPackageServiceImpl.updateRelease` (read while investigating
  `addOrUpdateRelease`) throws a bare `IllegalStateException` in what reads as an unreachable branch;
  noted only, not independently confirmed live (no code path in this harness's own scenarios reaches
  it).

### PyPI protocol-specific suite (step 5h, RPS-294)

`tests/pypi/protocol-specific.spec.ts`, ported from `repsy-cloud`'s own e2e harness
(`protocols/pypi/setup.template.py`'s `setup.py sdist bdist_wheel` + `util.ts`'s
`pypiUpload`/`pypiInstall`, which upload a wheel AND an sdist together and never probe an
sdist-only install — read-only reference material, never a target this repo modifies) into real
`twine`/`pip` binaries and a hand-built repo+token layout, this harness's own conventions — never
that harness's own `npx tsx subprocess`/`shelljs`/`setup.py` structure. Goes beyond repsy-cloud's
own coverage: it publishes an sdist-ONLY package (no wheel sibling) and confirms live what a real
`pip install`/`download` does when only a source archive exists — the exact question README.md's
own H20 (PyPI runner section) left open ("dropped per the plan's own 'if time-boxed' escape
hatch").

`buildSdist` (`pypi-raw.ts`) hand-assembles the `.tar.gz` the same way `buildWheel` hand-assembles a
wheel — `docker-image.ts`'s own ustar `buildTar` plus `zlib.gzipSync`, never `python -m build
--sdist`/`setup.py sdist`/`setuptools` (this story's own "own the exact bytes" constraint, and
`runners/pypi.Dockerfile` does not even install `setuptools`). Reading
`PackageStorageUtils.checkArchiveFilename`/`AbstractPypiProtocolFacade.uploadPackage` first showed
why no new server-side probing was needed: the upload grammar already accepts `.tar.gz` alongside
`.whl`/`.zip`, and the handler never reads the form's `filetype`/`pyversion` fields at all — a wheel
and an sdist take the identical server-side code path.

- **A real `twine upload` of a hand-built sdist (no `setup.py`/`pyproject.toml` at all) publishes,
  lists and downloads correctly**: confirmed live — exit `0` on the first attempt (twine's own
  `pkginfo.SDist` metadata parser reads `PKG-INFO` directly and needs neither file); the served
  project page carries the correct `href`/`#sha256=`, and `Requires-Python` (from `PKG-INFO`, via
  twine's form) round-trips through the HTML escape/unescape exactly like a wheel's `METADATA`
  header does.
- **Pip cannot resolve a source-only package on this runner image — live-confirmed, not a Repsy
  bug**: a plain `python3 -m pip download --no-deps --dest <dir> <name>==<version>` (deliberately
  WITHOUT `--only-binary=:all:`, the flag the catalog loop's own `resolve()` always passes) against
  an sdist-only package downloads the archive fine, then refuses to install it OUTRIGHT — `ERROR:
... does not appear to be a Python project: neither 'setup.py' nor 'pyproject.toml' found`, exit
  `1`, promptly, no hang, no build-isolation attempt at all. `buildSdist` (`pypi-raw.ts`)
  deliberately never writes either file (this story's own "own the exact bytes, no setuptools"
  constraint, the sdist analogue of `buildWheel` never invoking `setuptools`/`wheel`), and pip's own
  legacy-vs-PEP-517 project-type detection runs BEFORE it would ever attempt a build — so this is an
  even earlier, simpler failure than a build-isolation dependency fetch (this suite's own initial,
  manual-probe prediction, corrected once the real suite ran — see the spec file's own header). Not
  a Repsy bug — the server already serves the archive correctly (the previous test's own assertions
  prove that); no real client in this harness ships a PEP 517 build backend, that was simply never
  part of `pypi.Dockerfile`'s own toolchain, and this hand-built sdist was never meant to be
  pip-installable from source in the first place. A failed resolve leaves nothing under `--dest`.

The Helm alias-flow sub-scope originally planned alongside this PyPI work (`helm repo add` + `helm
repo update` + `helm search repo` + `helm pull <alias>/<chart>`) turned out to be already fully
covered by `tests/helm/classic-publish-consume.spec.ts`'s existing "C1" test (added in the Helm
runner step) — checked first, confirmed by reading that file, so nothing new was added for Helm in
this step; see the "Helm runner" section above for C1's own coverage.

## Go runner

Go is the first protocol in this harness with **no official publisher at all**:
`repsy-protocols/golang/README.md`'s "Uploading a Module" section is explicit that Repsy is a push
registry ("Repsy does not run `go mod` commands. You build the zip locally and upload it with a
single `curl`"), and the panel's own `golang-config.component.ts` teaches the identical incantation.
So `clients/golang.ts`'s `publish`/`seedPublish` drive the REAL `curl` binary directly, and there is
no companion raw-HTTP probe the way pypi's/nuget's/cargo's `publish()` needs one: `curl -w
'%{http_code}'` already reports the raw HTTP status (confirmed live: `--fail-with-body` still writes
the response body to `-o` and prints the status code on a 4xx/5xx, curl exit `22`). `resolve` drives
the REAL `go` toolchain (`go mod download -json`), the consume side every protocol in this harness
gets.

Every module zip is hand-built (`golang-raw.ts`'s `buildModuleZip`, `fflate` — never `go build`/
`go mod`), rendered from `go.template.mod`/`hello.template.go` with a fresh random marker packed into
`hello.go`/`e2e-marker.txt`. There is deliberately **no redeploy/prerelease-sibling trick** the way
cargo's adapter needs one: version immutability is unconditional (`allowOverride` is never read
anywhere in either Go package, grep-confirmed), so `no-override`/`override` both simply pin a real
`409` in `catalog.ts` and every scenario's own `publish()` call already IS the real, final attempt.

The decisive design fact, read from `cmd/go/internal/web/http.go` and confirmed live BEFORE any
adapter code was written (H3): a real `go` command refuses outright to pass Basic credentials to an
explicit `http://` `GOPROXY` URL (`refusing to pass credentials to insecure URL: ...`, client-side,
before any request), while the exact same credentials work over `https://` (H4). Since this harness's
own stack is plain HTTP, a credentialed consume needs an in-process HTTPS terminator in front of it —
`clients/golang-tls-shim.ts`'s `ensureTlsShim()`, a lazily started, per-worker-process Node
`https.createServer` reverse proxy bound to `127.0.0.1:0`, trusted via `SSL_CERT_FILE` alone (no
`--ca`, confirmed live/H4) pointed at a throwaway certificate/key `runners/golang.Dockerfile` generates
ONCE at build time with Go's own `crypto/tls/generate_cert.go`. An anonymous consume, or one already
against `https://` (a remote target), never needs the shim at all (H2).

```bash
./run.sh test --protocol golang -b   # -b the first time: builds the golang runner image
```

### Scenario mapping onto the shared catalog

Every catalog scenario that is not maven/nuget-restricted applies to golang unchanged, with the SAME
`unauthorized`/`ok` buckets maven already pins — `GolangAuthComponent` is a bare `ProtocolAuthService`
subclass with no overrides, so every auth outcome (a read-only token's flat 401 on WRITE, an expired/
revoked/rotated/wrong-repo token, a wrong password, anonymous-on-private) matches byte-for-byte,
confirmed live. The ONE data change needed: `no-override`/`override` both get `expectByProtocol: {
golang: { publish: 'conflict' } }` — a REAL, UNCONDITIONAL `409` (`goModuleVersionAlreadyExists`),
confirmed live, since `allowOverride` is never read at all (so, like cargo's own deliberate note,
`override: true` does NOT make a redeploy succeed for golang either). golang is never added to
`maven-releases-off`/`maven-snapshots-off`/`redeploy-*-off`/`snapshot-*`: it has no releases/snapshots
rule at all (grep-confirmed: no Go code reads either repo setting) and no SNAPSHOT-file concept.

`registry-rules.spec.ts` additionally pins (raw HTTP, no `curl`/`go` client): the read-only-token auth
matrix (R1); every accepted upload-URL spelling — no suffix, `.zip`, and even `.mod` with a zip body,
G6 (R2); `Content-Sha256` verified when present (case-insensitively), ignored when absent (R3);
immutability + no storage side effect under BOTH `allowOverride` settings (R4/H9); zip-validation
errors leaving nothing stored (R5); `@v/list`'s real-semver sort and empty-body-for-unknown-module
shape (R8); `@latest`'s DB-backed highest-version selection (R9); a malformed module path's bodyless
400 (R11); `sumdb/supported` 404ing on both ports (R12/G9); over-long module-path/version refusal
(R13); a deleted version's clean re-upload, never a `410` (R14/RPS-1230); `HEAD` always 404ing,
the opposite of pypi's always-200 quirk (R15/H17); and that `releases`/`snapshots` are never read
(R16). Three backend bug candidates are pinned with `test.fail()` (G1/G2/G10, below).

### H1-H20, confirmed live

Every hypothesis below was probed against a running instance (`./run.sh local up`) with raw
`curl`/Node `fetch` and a throwaway `golang:1.27.1-bookworm` container (`docker run --network host`)
BEFORE any adapter code was written — H1-H4 and H12 gated the whole design.

- **H1** (Repsy is push-only: a raw PUT of a hand-built zip lands `.mod`/`.zip`/`.info` and
  `@v/list` lists it; nothing contacts an upstream): confirmed live on the FIRST attempt.
- **H2** (`go mod download -json` against a public repo, no credentials, no shim, succeeds; `Zip`
  bytes sha256 equal the uploaded ones; `Dir/e2e-marker.txt` exists; `Sum`/`GoModSum` equal the TS
  dirhash reference): confirmed live — see `publish-consume.spec.ts`'s dedicated cross-check test.
- **H3** (a credentialed `http://` `GOPROXY` fails client-side, exit 1, "refusing to pass
  credentials to insecure URL"): confirmed live, gating the whole TLS-shim design (see "Go runner"
  above) — also pinned as a real-client test, `publish-consume.spec.ts`'s plain-http-creds test.
- **H4** (through the shim, the same command succeeds on a PRIVATE repo with `SSL_CERT_FILE` alone,
  no `--ca`; both `user:password` and `token:<deploy-token>` work): confirmed live.
- **H5** (`/usr/local/go` copied into `node:24-bookworm-slim` runs `go version`/`go mod download`/
  `go build` with no extra apt packages beyond `curl ca-certificates`; `go run generate_cert.go`
  works at build time): confirmed live — `runners/golang.Dockerfile` built clean on the first
  attempt.
- **H6** (`go get`, then `go build`, then running the binary, prints the marker): confirmed live —
  `publish-consume.spec.ts`'s dedicated test.
- **H7** (no toolchain download attempt, no sumdb traffic): not independently wire-traced, but
  inferred with high confidence from `GOTOOLCHAIN=local` (a version mismatch would fail loudly, and
  none did across the whole suite) and `GONOSUMDB=e2e.repsy.test` plus the module domain's own
  non-resolution (the `.test` TLD never dials out at all) — no test failed in a way a toolchain/sumdb
  fetch attempt would produce (timeout/DNS error), across two full local runs.
- **H8/H19** (the wire sequence for an exact-version consume includes `.info`, `.mod` and `.zip`):
  confirmed live — `publish-consume.spec.ts`'s TLS-shim wire-trace test asserts all three routes were
  requested for one `go mod download`.
- **H9** (a duplicate PUT is refused with 409 under both `allowOverride` settings; stored files
  unchanged): confirmed live — `registry-rules.spec.ts`'s R4 test.
- **H10** (every negative scenario fails the client promptly, no retry storm): confirmed — the whole
  negative-scenario `test.describe` block (parallel, 12 workers) completed in under a second per
  scenario across two full runs, and `go` itself has no built-in HTTP retry logic.
- **H11** (`Seeder.cleanup()` deletes a Go repo holding modules/versions; a sweep afterwards lists
  nothing): confirmed by construction — every test in this suite ran under the shared `seeder`
  fixture and left no `e2e-*` repos behind (checked with `./run.sh sweep --all --dry-run` after the
  full verification run, see "Verification" below).
- **H12** (the `fflate` zip, no directory entries, passes both Go's entry-prefix check and its unzip
  check): confirmed live — the very first raw PUT/`go mod download` round trip succeeded.
- **H13** (Repsy's `.info` `Time` parses into a real instant): confirmed live —
  `afterSuccessfulRoundTrip`'s check runs on every `ok`/`ok` catalog scenario.
- **H14** (the panel's literal curl, no `Content-Type`, is accepted): confirmed live while probing
  R2/R3 — every raw upload in this suite that omits `Content-Type` still succeeds; the adapter itself
  sends one explicitly regardless (this file's header, the maven-adapter lesson).
- **H15** (parallel workers each start their own shim on an ephemeral port; two consecutive full
  `--protocol golang` runs are green without a stack reset): confirmed — both runs passed 35/35 with
  no port conflict and no stack reset in between.
- **H16** (`v0.<secs>.<seq>` is accepted by both Repsy and Go; `@latest` returns it when it is the
  only version): confirmed live.
- **H17** (what the router answers to `HEAD .../@v/<v>.info`): confirmed live — `404`, not pypi's
  `200` (see R15 above; neither protocol method handler lists `HEAD` among its supported methods, so
  the router has nothing to dispatch to).
- **H18** (a mixed-case module path real round trip): confirmed live —
  `publish-consume.spec.ts`'s dedicated test: a real `go mod download -json` of a module path with a
  mixed-case last segment succeeds and reports back the ORIGINAL (not lower-cased) path in its own
  `Path` field.
- **H20** (`go mod download -json` on a 401 prints a JSON object with `Error` and exits 1): confirmed
  live — the plain-http-creds test's own `Error` field, and every negative catalog scenario's `resolve`
  step parses a JSON object with no `Zip` field on a 401.

### Backend bug candidates found while reading and confirmed live (do not fix here)

- **RPS-1227** — `AbstractGoProtocolFacade.upload` never validates the version string against
  Go's own semver grammar at all: `banana` is accepted (`200`), stored immutably and listed by
  `@v/list`, even though it is not a valid Go semver string a real `go` command's own parser would
  ever produce or accept. Confirmed live: `registry-rules.spec.ts`'s non-semver test (`test.fail()`).
- **RPS-1228** — `GoModFileValidator` never compares the go.mod `module` directive against the
  URL's own module path: a zip whose go.mod names a COMPLETELY DIFFERENT module still uploads
  successfully under the URL's path. A real `go get` of that path then fails once it validates the
  downloaded go.mod against the path it asked for. Confirmed live: `registry-rules.spec.ts`'s
  mismatch test (`test.fail()`).
- **RPS-1231** (observation only, not wire-observable, no test) — `GoModuleHashCalculator`'s
  stored `h1:` hashes are NOT `golang.org/x/mod/sumdb/dirhash.Hash1`-compatible: the real algorithm's
  inner line is `"%x  %s\n"` (hex digest, TWO spaces, then the file/entry name, entries sorted by
  NAME alone before the lines are built — confirmed live by reproducing a real `go mod download
-json`'s own `Sum`/`GoModSum` byte-for-byte, see H2/`dirhashHash1`), while
  `GoModuleHashCalculator.hashMod`/`hashZip` build `"<name>:<hex>\n"` (colon, name FIRST, and for the
  zip hash, sort the already-built lines rather than the entry names — which reorders differently
  whenever two entries' hex digests collide in sort order with their names). Not independently
  observable over the wire: `GoModuleVersionListItem` (the panel API DTO) exposes no hash field at
  all, and the `go` command never reads Repsy's own stored hash, only computes its own from the
  downloaded bytes — noted here from source for the coordinator's own report, not asserted by any
  test in this harness.
- **G4** (fixed, RPS-1124/#511; was an observation only, no test) — the DB row used to commit BEFORE
  the three storage writes in `AbstractGoProtocolFacade.upload` (`goModuleService.publishModule`
  ran first, then `writeModFile`/`writeInputStreamToPath`/`writeInfoFile`), so a failed file write
  left a committed row without its files. The row is now flushed first and stays uncommitted while
  the files are written; a failed write rolls it back and removes the partly written files.
- **G5** (architectural observation from source, not independently forced live; mentioned in the same
  [RPS-1124](https://zyfera.atlassian.net/browse/RPS-1124) comment as G4, same root cause) —
  `@v/list` and `@latest` read DIFFERENT sources of truth: `handleVersionList` lists the STORAGE
  directory, `handleLatestVersion` reads the DB (`findLatestPublishedVersion`). Every scenario this
  harness's own catalog exercises keeps both in sync (a successful upload always writes both), so this
  was not independently forced out of sync live.
- **G6** (not a defect — documented, deliberate behavior, no ticket filed) — `PUT .../@v/<v>.mod`/
  `.info` is silently treated as a `.zip` upload: only the
  LAST path segment's known extension is stripped to find the version, nothing else looks at the
  suffix. Confirmed live: `registry-rules.spec.ts`'s upload-URL-spellings test (R2) — not routed
  around with `test.fail()` since this is documented, deliberate behavior (`GoVersionUtils
.extractVersionFromPath`'s own javadoc), not a defect the catalog loop needs to work around.
- **RPS-1229** (same family as RPS-1214/RPS-1222, docs bugs on other protocols) — the panel's
  own documented `go env -w GOPROXY="<scheme>://user:pass@..."` incantation
  (`golang-config.component.ts`) cannot work AT ALL on a plain-http deployment: the `go` command
  itself refuses to send it (H3). Confirmed live: `publish-consume.spec.ts`'s dedicated test.
- **RPS-1230** (resolved) — the `410 Gone`/`GoVersionGoneException` path was DEAD: grep-confirmed
  nothing in either Go package ever threw it, and the `deleted` column `V0002__Golang_Protocol.sql`
  once created was itself already dropped by `V0004__Drop_Deleted_Column.sql`, so only the Java
  scaffolding (the exception class, its unreachable `catch`, and its unreachable
  `@ExceptionHandler`) was left. That scaffolding has now been deleted; hard-delete stays the only
  deletion semantics. Deleting a version through the panel API removes it OUTRIGHT (DB row and all
  three storage files), and re-uploading the exact same version afterwards succeeds cleanly with a
  fresh `200`, never a `410`. Confirmed live: `registry-rules.spec.ts`'s R14 test, unchanged by the
  cleanup since the observable behavior was identical before and after.
- **G9** (not a defect — the observed status is correct on both ports, just by two unrelated code
  paths, no ticket filed) — `sumdb/supported` 404s on BOTH the API port (`GolangModuleController
.checkSumdbSupported`, deliberate — the doc comment says so) and the protocol port (the `go` command's
  own probe lands on the download handler and 404s by a plain storage-miss, purely by accident — a
  different code path producing the same status). Confirmed live: `registry-rules.spec.ts`'s R12
  test.
- **RPS-1232** — module paths are LOWER-CASED for storage/lookup (`AbstractGoProtocolFacade
.upload`'s `normalizedPath`/`AbstractGoProtocolFacade.decodePath`), so a mixed-case upload is stored
  under the SAME module as its lower-case spelling, even though real Go treats module paths as
  case-SENSITIVE identity. Confirmed live twice: a raw-HTTP collision test (`registry-rules.spec.ts`,
  `test.fail()`) and a real-client round trip showing the mixed-case path still resolves through the
  lower-cased storage (`publish-consume.spec.ts`'s H18 test — NOT `test.fail()`-pinned there, since a
  real `go get` succeeding is itself the correct, desired behavior; only the raw-HTTP "are these two
  DISTINCT modules" test is pinned as a candidate).

## Ruby runner

This is **step 4e ("ruby") — the LAST protocol adapter of step 4**: the Ruby gem (RubyGems/Bundler)
client adapter and runner. It closes out step 4 with a real-toolchain publisher/consumer pair, like
pypi/golang, and it is the protocol whose plan carried the single most consequential gating
hypothesis of the whole harness — refuted live before any adapter code was written (see below).

`runners/ruby.Dockerfile` copies a pinned Ruby toolchain (`ruby:4.0.7-slim-bookworm`, confirmed live
to ship RubyGems 4.0.20 / Bundler 4.0.20 — unified versioning since Ruby 4.0) in from that official
image's `/usr/local/{bin,lib/ruby,lib/libruby.so*}`, the same "copy the toolchain, not the whole
image" approach as pypi's CPython / golang's Go toolchain. `clients/ruby.ts` renders
`src/packages/ruby/{Gemfile,lib}.template.*` (plus `metadata.template.yaml` for the hand-built `.gem`
itself, `ruby-raw.ts`'s `buildGem`) into a per-invocation isolated work directory (`clients/exec.ts`)
and runs the REAL `gem`/`bundle` binaries:

- **`publish`**: `gem push <file>.gem --host <repoBaseUrl>/<repo>` (no `--key` — the panel's own
  documented incantation minus `--key`; the credential goes ONLY through `GEM_HOST_API_KEY`), followed
  by a byte-identical raw-HTTP re-POST of the same gem bytes (the maven/npm/nuget/pypi pattern: a real
  override rule exists, confirmed live, so this is a harmless accepted replacement under the fixture
  default `allowOverride: true`, and the same `409` under `false`).
- **`resolve`**: a rendered `Gemfile` (`source "<repoBaseUrl>/<repo>" do gem "<name>", "= <version>"
end`) and a real `bundle install --verbose`, plus an auth-only raw `GET /info/<name>` companion
  probe (never the `.gem` bytes themselves, mirroring pypi's/golang's own `resolve()` reasoning).
- **Every publish/seed-publish packs a fresh random marker** into both `lib/<name>.rb` and
  `e2e-marker.txt`; `AdapterResult.contentSha256` is the sha256 of the WHOLE `.gem` file (Bundler's
  cache renames the downloaded gem into place byte-for-byte, confirmed live).
- **`packageName(runId, scenario)` is underscore-only** (`e2e_<runid>_<scenario.id, underscored>`),
  deliberately NOT hyphenated like every other protocol's `packageName` — see "RPS-1236 (fixed)" below
  for why; the helper stays underscore-only regardless of the fix.
- **Credential mapping** (`ruby-raw.ts`'s `apiKeyFor`/`bundleCredentialsValue`, both confirmed live):
  `GEM_HOST_API_KEY` for `gem push` (the raw deploy-token secret for a `token`-kind credential,
  `Basic <base64(user:pass)>` for a `password`-kind one); `BUNDLE_<HOSTKEY>=user:secret` (Bundler's own
  host-keyed `Settings.key_for`, confirmed live/H5 — `BUNDLE_LOCALHOST` for this harness's own
  `localhost` target) for `bundle install`. `anonymous` leaves both entirely unset.

```bash
./run.sh test --protocol ruby -b   # -b the first time: builds the ruby runner image
```

### H1, confirmed live: the plan's central gating hypothesis is REFUTED

The plan's single most consequential prediction was that a real `bundle install` could not
successfully consume from a Ruby repo on Repsy OS at all, because `quick/Marshal.4.8/*.gemspec.rz`
has no backend route (RPS-1233, grep-confirmed: no class extends `AbstractRubyGemspecHandler`) and
`/info` never advertises `ruby:`/`rubygems:` requirement keys (RB-2). This was probed live FIRST,
before any adapter code was written: a hand-built gem was raw-POSTed to a local stack, then a
throwaway `ruby:4.0.7-slim-bookworm` container (`docker run --network host`) ran `bundle install
--verbose` against it with the Gemfile/env this file's own adapter now uses.

**The real outcome: `bundle install` succeeds completely, exit 0.** Its own verbose trace shows
exactly two server requests — `GET /versions` and `GET /info/<gem>` — followed by "Fetching
probe_gem 0.1.0" / "Downloaded ... / Installed ...", with NO `gemspec.rz` request at all. The gem
file Bundler leaves under `<BUNDLE_PATH>/ruby/4.0.0/cache/probe_gem-0.1.0.gem` is byte-identical
(sha256-equal) to what was published. Bundler's `EndpointSpecification#_remote_specification` (the
mixin that would trigger a `fetch_spec` -> `gemspec.rz` request) is a LAZY fetch, only invoked when
something actually reads `required_ruby_version`/`dependencies` beyond what the compact-index `/info`
line already carried inline — and this harness's own dependency-free fixture gem never does, so the
lazy fetch is simply never triggered. **Consequently `ruby.ts`'s `resolve()` drives the real `bundle`
toolchain with NO `knownConsumeFailure` hook at all** — every scenario's consume side is asserted for
real, exactly like maven/npm/pypi, per the plan's own explicit fallback instruction for a refuted H1.

RPS-1233 was real (confirmed live, `registry-rules.spec.ts`'s R10 test) and broke `gem install
--source .../gem/`; it is now fixed by a concrete `RubyGemspecHandler` that registers the route, and
both `registry-rules.spec.ts`'s R10 test and `publish-consume.spec.ts`'s dedicated `gem install`
real-client test assert the fixed behavior instead of pinning the failure. `gem fetch` shared the
same route dependency plus its own separate bug (RPS-1234, the zlib/gzip mismatch below); both are
now fixed and `publish-consume.spec.ts`'s dedicated `gem fetch` test asserts a real exit 0 — neither
`gem` subcommand routes through the catalog loop's own consumer (`bundle install`, per H1 above).

### Scenario mapping onto the shared catalog

Every catalog scenario that is not maven/nuget-restricted applies to ruby unchanged, with the SAME
`unauthorized`/`ok` buckets maven already pins — `RubyAuthComponent` is a bare `ProtocolAuthService`
subclass with no overrides (`normalizeAuthHeader` Bearer-prefixes a bare value, the cargo/golang
trick), so every auth outcome (a read-only token's flat 401 on WRITE, an expired/revoked/rotated/
wrong-repo token, a wrong password, anonymous-on-private) matches byte-for-byte, confirmed live. The
ONE data change needed: `no-override` gets `expectByProtocol: { ruby: { publish: 'conflict' } }` — a
REAL `409` (`gemVersionAlreadyExists`, `RubyGemServiceImpl.upsertVersion`: an existing version that is
either yanked or published under `allowOverride:false` is refused), confirmed live. `override` needs
no data change: an existing, non-yanked version under `allowOverride:true` is overwritten in place, so
the shared `ok` pin already matches. ruby is never added to `maven-releases-off`/`maven-snapshots-off`/
`redeploy-*-off`/`snapshot-*`: it has no releases/snapshots rule at all (grep-confirmed: no Ruby code
reads either repo setting) and no SNAPSHOT-file concept.

`registry-rules.spec.ts` additionally pins (raw HTTP, no `gem`/`bundle` client): the auth matrix and
Authorization spellings (R1/R3); the happy-path shape of `/names`/`/versions`/`/info`, including the
RB-2 observation that no `ruby:`/`rubygems:` keys are ever emitted (R2/R9); the override rule's
row-first ordering, re-verifying RPS-1060 still holds for Ruby (R4); malformed-gem 400s leaving
nothing stored (R6); the full yank flow — success, re-yank refusal, a read-only token/USER-role
password both refused with 401 (`MANAGE` permission), a yanked version's `.gem` file staying
downloadable (R8, RPS-1238, fixed), and a yanked version rejecting even an `allowOverride:true`
re-push (R8); a panel-API delete (not a yank) allowing a clean re-publish (R5/R16); `specs.4.8.gz`'s
gzip framing (RFC 1952, `gunzipSync` succeeds and `inflateSync` throws) and the prerelease/latest
split (R9, RPS-1234, fixed); the `gemspec.rz` route (R10, RPS-1233, fixed); unknown-gem 404s and an
empty repo's listings (R11); `HEAD` mirroring GET's status (R12, RPS-1237, fixed); a platform gem's
filename/info-line shape and yank's explicit-platform requirement (R14); that `releases`/`snapshots`
are never read (R15); and RPS-1236's hyphen-before-digit download case (R13, fixed).

### H1-H20, RPS-1233-RPS-1238: confirmed live

Every hypothesis and backend-bug candidate below was probed against a running instance
(`./run.sh local up`) with raw `curl`/Node `fetch` and throwaway `ruby:4.0.7-slim-bookworm` containers
(`docker run --network host`) — H1-H3, H5, H6 gated the whole design and were probed FIRST, before any
adapter code was written.

- **H1** (a real `bundle install` succeeds against Repsy OS): **REFUTED as predicted** — see the
  dedicated section above. This is the single most consequential finding of this step.
- **H2** (a `.gem` hand-built entirely in TypeScript — `docker-image.ts`'s `buildTar` + `node:zlib`,
  never `gem build` — passes BOTH `gem push`'s client-side `Gem::Package#verify` AND Repsy's own
  `GemspecParser`): confirmed live on the first attempt, no `gem build` fallback ever needed. The
  canonical `metadata.gz` YAML shape (`metadata.template.yaml`) was captured from one real `gem build`
  run in a throwaway container, then reproduced byte-faithfully in TypeScript.
- **H3** (`GEM_HOST_API_KEY=<raw deploy token>` → 200; `GEM_HOST_API_KEY="Basic <b64 user:pass>"` →
  200 for both admin and a USER-role user; an unprefixed raw password → 401): confirmed live — see
  `publish-consume.spec.ts`'s USER-role-Basic test and `registry-rules.spec.ts`'s Authorization-
  spellings test.
- **H4** (anonymous `gem push`, no key, no credentials file, closed stdin, exits promptly, no hang):
  confirmed live — the client's own interactive sign-in hits `POST /api/v1/api_key` on this harness's
  own instance, which 404s (`unknownPath`, that route is not implemented at all), and `gem push` exits
  1 before ever attempting the real push request.
- **H5** (`BUNDLE_<HOSTKEY>=user:secret`, host-keyed — confirmed as `BUNDLE_LOCALHOST` for this
  harness's own `localhost` target — makes Bundler send Basic auth on every request over plain http;
  token AND password credentials both work, including a password containing `!`, unescaped): confirmed
  live against a private repo; no `.bundle/config` file fallback was ever needed.
- **H6** (the copied `/usr/local/{bin,lib/ruby,lib/libruby.so*}` + the `ldd`-derived apt install list —
  `ca-certificates libssl3 libyaml-0-2`, cross-checked against a bare `node:24-bookworm-slim` rather
  than guessed — runs `ruby`/`gem`/`bundle` and the `psych`/`openssl`/`zlib`/`digest`/`fiddle`/
  `bigdecimal`/`etc` smoke line): confirmed live, first build attempt.
- **H7** (`bundle install`'s cached `.gem` is byte-identical to what was published): confirmed live —
  `publish-consume.spec.ts`'s dedicated end-to-end test.
- **H8** (a byte-identical raw re-POST under `allowOverride:true` → 200; under `false` → 409, nothing
  changed on disk): confirmed live — `registry-rules.spec.ts`'s R4 test, and this is exactly the
  catalog loop's own `publish()` companion-probe pattern.
- **H9** (every negative scenario fails promptly): confirmed — the negative `test.describe` block
  (parallel workers) completed well under the per-test timeout across two full runs, and neither
  `gem`/`bundle` retries a refused request by default.
- **H10** (`md5Hex(/info body)` matches `/versions`' own md5 after publish and after yank): confirmed
  live — `registry-rules.spec.ts`'s happy-path-shape test and the yank test both assert it.
- **H11** (`specs.4.8.gz` is zlib not gzip; `gem fetch` fails on it): confirmed live, then **fixed** —
  RPS-1234; the backend now serves real gzip (`GZIPOutputStream`), asserted directly in the
  dedicated `gem fetch` real-client test, which now also exits 0 end to end since RPS-1233 (below)
  removed the other bug `gem fetch` shared with `gem install`.
- **H12** (`gem install --source` failed on the missing `gemspec.rz` route): confirmed live — RPS-1233,
  now fixed; the dedicated `gem install` real-client test asserts the fixed behavior instead of
  pinning the failure.
- **H13** (a yanked version is listed in `/info` prefixed `-`, not omitted): confirmed live — RPS-1235,
  now fixed (`/info` omits it entirely; the `-` prefix stays the `/versions` convention).
- **H14** (a gem named with a `-<digit>` segment publishes but cannot be downloaded): confirmed live —
  RPS-1236, now fixed (`registry-rules.spec.ts`'s dedicated test, no longer `test.fail()`-pinned);
  `packageName()` stays underscore-only regardless, avoiding the case by construction rather than
  relying on the fix alone.
- **H15** (`Seeder.cleanup()` deletes a Ruby repo holding gems; a sweep afterwards lists nothing; two
  full runs without a stack reset are identical): confirmed by construction — every test in this suite
  ran under the shared `seeder` fixture and left no `e2e-*` repos behind (see "Verification" below).
- **H16** (binstubs — `gem`/`bundle`/`bundler` in `/usr/local/bin` — work correctly with overridden
  `GEM_HOME`/`GEM_PATH`): confirmed live (part of H6): every real client invocation in this suite
  overrides both and every one succeeded/failed exactly as expected.
- **H17** (Bundler's checksum store accepts Repsy's `checksum:` with no `ChecksumMismatchError`):
  confirmed live — every `ok`/`ok` catalog scenario's `bundle install` succeeded with no checksum
  complaint, and `publish-consume.spec.ts`'s dedicated end-to-end test asserts it explicitly.
- **H18** (`/versions`' `created_at` differs per request; `/info`'s is stable across an unrelated
  request): confirmed live — this is exactly why `RubyFingerprint` (`ruby.ts`) deliberately never
  includes `/versions`.
- **H19** (a yanked version cannot be re-pushed even under `allowOverride:true`; a panel-deleted
  version can): confirmed live — `registry-rules.spec.ts`'s yank test and panel-delete test.
- **H20** (`1.0.0.pre1`/`2.0.0.beta`-style versions publish; `releases`/`snapshots` are refused on a Ruby repo since RPS-1210):
  confirmed live — `registry-rules.spec.ts`'s R15 test; Ruby has no release/snapshot repo-setting
  concept at all (grep-confirmed).

### Backend bug candidates found while reading and confirmed live (do not fix here)

- **RPS-1233 (fixed)** — `quick/Marshal.4.8/<name>-<version>.gemspec.rz` had no backend route at all:
  no class under `repsy-backend`'s Ruby package extended `AbstractRubyGemspecHandler` (grep-confirmed),
  even though the abstract handler, `RubyGemspecMarshalWriter` and `RubyProtocolFacade.getGemspec`
  were all already implemented in `repsy-protocols/ruby`. The router's catch-all answered `404
unknownPath`. Broke `gem install --source`/`gem fetch`; did NOT break `bundle install` (H1's
  refutation, the headline finding of this step). Was the highest-severity candidate of this step,
  since it silently dropped an entire, otherwise-implemented feature from being reachable. Fixed by a
  concrete `RubyGemspecHandler` that registers the route: `gem install` now succeeds end-to-end
  (`publish-consume.spec.ts`) and `registry-rules.spec.ts`'s R10 test asserts `200`. `gem fetch`
  shared this route dependency plus its own separate RPS-1234 zlib/gzip bug below; both are now
  fixed and `gem fetch` succeeds end-to-end too.
- **RB-2** (observation) — `/info/<gem>` never emits `ruby:`/`rubygems:` requirement keys
  (`CompactIndexFormatter.appendVersionLine`), even though `required_ruby_version` is correctly parsed
  by `GemspecParser` and stored on `ruby_gem_version.required_ruby_version`. This is architecturally
  why H1 refutes the plan's prediction: Bundler's lazy remote-spec fetch (which WOULD need RPS-1233's
  missing route) is only triggered by a version's `required_ruby_version` being genuinely absent from
  `/info` in a way that forces a fallback check — and it happens to just... not need one for
  resolution to succeed. Confirmed live, `registry-rules.spec.ts`'s happy-path-shape test.
- **RPS-1234 — FIXED.** `specs.4.8.gz`/`latest_specs.4.8.gz`/`prerelease_specs.4.8.gz` were compressed
  with `java.util.zip.DeflaterOutputStream` (raw zlib/RFC1950), not gzip (RFC1952) as their own `.gz`
  filenames and a real `gem fetch`/the legacy Index fetcher both expect. Confirmed live before the
  fix: `gunzipSync` threw, `inflateSync` succeeded and yielded a valid Marshal 4.8 stream; a real
  `gem fetch` failed. Fixed by swapping in `java.util.zip.GZIPOutputStream`
  (`AbstractRubySpecsIndexHandler`); `Content-Type` stays `application/octet-stream` and no
  `Content-Encoding: gzip` header is added (the gzip framing is the file's own content, not a
  transfer encoding — adding that header would make an HTTP client transparently decompress it and
  hand RubyGems a bare Marshal stream to gunzip a second time). `registry-rules.spec.ts` (R9) now
  asserts `gunzipSync` succeeds and `inflateSync` throws. The dedicated `publish-consume.spec.ts`
  real-client `gem fetch` test asserts the same gzip framing directly and, now that `gem fetch`'s
  other shared dependency (the `gemspec.rz` route, RPS-1233) is also fixed, asserts a real exit 0
  end to end. Note: `AbstractRubyGemspecHandler.deflate` (the `.rz` gemspec route, RPS-1233) is a
  different, near-identical-looking method that correctly uses raw zlib per the RubyGems spec — it
  was deliberately left untouched.
- **RPS-1235 (fixed)** — a yanked version used to be listed in `/info/<gem>` with a `-` prefix instead
  of being OMITTED entirely, which is what the compact-index spec requires (the `-` prefix is the
  `/versions` endpoint's own convention, not `/info`'s). Fixed by filtering yanked entries inside
  `CompactIndexFormatter.buildInfoBody`, computed from the unfiltered list so `created_at` stays
  correct even for an all-yanked gem. `registry-rules.spec.ts`'s happy-path test and
  `publish-consume.spec.ts`'s dedicated yank test assert the omission directly now (no longer
  `test.fail()`-pinned).
- **RPS-1236 (fixed)** — a gem NAME containing a hyphen immediately followed by a digit (e.g. `foo-2fa`)
  published successfully (the DB row keys off the name/id, not the filename) but its `.gem` file could
  never be downloaded: `AbstractRubyStorageService`'s `extractGemName`/`VERSION_START` pattern
  (`-(?=\d)`) cut the filename at the FIRST such boundary, looking the file up under storage key `foo`
  instead of `foo-2fa`, which 404d. Fixed by resolving the filename against the DB instead
  (`GemFilenameCandidates.split` tries every candidate `(name, version, platform)` reading,
  longest-name-first, validated against real rows via the new `findByGemFilename`); `getGem` now takes
  the resolved `(gemName, version, platform)` directly rather than parsing them from the filename.
  `registry-rules.spec.ts`'s dedicated test asserts the download succeeds now (no longer
  `test.fail()`-pinned). `ruby.ts`'s/`ruby-raw.ts`'s `packageName()` stays underscore-only regardless
  (unlike every other protocol's hyphenated one), since `REPSY_E2E_RUN_ID` can start with a digit and
  a hyphenated scenario name would otherwise risk tripping this exact case by accident on every run.
- **RPS-1237 (fixed)** — `HEAD` on ANY path used to answer `200` empty, existence never checked at all
  (the pypi/nuget analogue). Fixed: `AbstractRubyHeadHandler` now dispatches per path kind with
  existence-only checks (`gemExists`/`gemFileExists`/`gemspecExists` on the facade, the `.gem` case
  reusing RPS-1236's `findByGemFilename` resolver) and mirrors the matching `GET` route's `200`/`404`.
  `registry-rules.spec.ts`'s dedicated test now asserts the mirrored status directly.
- **RB-7** (observation from source, not independently forced live) — `RubyGemDownloadHandler`'s
  `downloadGem` swallows every exception (`catch (Exception)`) into a bodyless `404`, so a genuine
  server error (a storage backend outage, say) would be indistinguishable from "this gem does not
  exist" to any client. Not independently forceable without breaking the storage backend itself, so
  this is noted for the coordinator's report rather than asserted by a test.
- **RB-8** (checked, NOT reproduced) — the plan speculated that `ruby_gem.latest` might track "last
  pushed" rather than "highest version" (`upsertGem` unconditionally calls `g.setLatest(version)` on
  every push, with no version comparison). This IS what the source shows, and pushing `1.0.0` then
  `0.9.0` does leave `latest` reading `0.9.0` on the panel API — but confirmed live this is genuinely
  what `latest` means throughout this codebase (the "most recently pushed version", not "highest
  semver"), consistent with `Gem::Specification#version=` semantics upstream (RubyGems itself has no
  built-in "highest version wins" latest-tracking behavior either — a real `gem push` never coerces
  ordering). Not filed as a bug: this is a naming/documentation nit at most, not a functional defect,
  and no test in this harness asserts a particular `latest` value.
- **RPS-1238 (fixed)** — a yanked version's `.gem` file used to answer `404` on download. Real
  rubygems.org keeps serving a yanked gem's file bytes (only the index stops advertising it), so
  existing `Gemfile.lock`s that pin a yanked version can still `bundle install` from cache/mirrors
  elsewhere; Repsy's `checkNotYanked` refused the file outright, breaking that same flow. Fixed by
  dropping the `checkNotYanked` check from `downloadGem` — yank now only unpublishes from the index,
  matching rubygems.org; a genuine takedown still goes through panel delete, which removes the row
  and the file. `registry-rules.spec.ts`'s yank test now asserts the download succeeds with unchanged
  bytes.

### RB-0 — RPS-1060 re-verified, no regression

`RubyGemServiceImpl.publishGem` still creates/updates and FLUSHES the DB row (a concurrent duplicate-
version race becomes a real `409` via the unique index) strictly BEFORE `AbstractRubyProtocolFacade
.storeGem` writes the file, and a storage write failure rolls the transaction back, deleting a
half-written NEW version's file (existing versions being replaced keep their row, so their file is
left alone on a failure — same as before). Confirmed live: `registry-rules.spec.ts`'s override test
re-verifies that a refused re-publish under `allowOverride:false` changes NOTHING on disk (byte-for-
byte). No regression from the RPS-1060 fix this codebase's other protocols' own equivalent stories
reference.

## Remote hardening

On a `remote` target (`target.isRemote`, see `src/target.ts`), `AUTH_THROTTLE_MAX_FAILURES` cannot
be raised the way `docker-compose.stack.yml` does for `local`/`ci`, so a `@negative` scenario's
failed-auth attempts have to stay under the server's own budget (20 failures / 60s per client) or
trip it for every client behind the same address, admin login included. `@negative`-tagged scenarios
run serially on a remote target instead of in parallel (`tests/maven/publish-consume.spec.ts`'s
`test.describe.configure({ mode: target.isRemote ? 'serial' : 'parallel' })`), and each reserves a
slot from `RemoteAuthBudget` (`src/scenarios/remote-throttle.ts`) first, waiting out the window
rather than firing the request and finding out from a 429; a protocol adapter's raw-HTTP checks back
off once and retry on an actual 429 via `withBackoff429`. `@local-only`-tagged scenarios (none exist
in the catalog yet — nothing in it needs a server restart or special env) are skipped on remote.
`tests/maven/remote-throttle.spec.ts` is a small, server-free sanity check of this accounting logic,
with an injected fake clock/sleep so it runs in milliseconds instead of really waiting.

Untested by this step (no remote instance to test against): a preflight check that verifies admin
login and refuses to run if the run prefix already exists, and never touching anything global on a
real shared remote. Both are called out in the plan as later, "remote hardening" work.

**golang-specific**: a remote target whose `REPSY_REPO_BASE_URL` is already `https://` needs no TLS
shim at all (`golang-tls-shim.ts`'s `needsTlsShim` — credentials embed directly into that URL). A
remote target that is plain `http://` with an UNTRUSTED certificate is unsupported for golang: Go has
no per-request "skip TLS verification" knob for a `GOPROXY` URL the way `curl -k`/`--insecure` does,
so there is no equivalent of `REPSY_E2E_INSECURE_REGISTRY` this protocol could honour.

## UI suite

Runs the real Angular panel (`repsy-frontend/`) in **headless Chromium** against the same built Repsy
image and stack the protocol runners use (epic RPS-1248). It is a Playwright project, `ui`, and a
runner service, `ui`, inside this harness rather than in `repsy-frontend/`: it needs the real stack,
the panel-API `Seeder` with its `e2e-<runid>-` naming and sweep, and the admin bootstrap, all of which
live here. Specs are `tests/ui/**/*.spec.ts`; the plumbing is `src/ui/`. `repsy-frontend/` keeps its
Karma unit tests.

### Running

```bash
./run.sh local up && ./run.sh test --protocol ui            # the whole UI suite, PostgreSQL stack
./run.sh test --protocol ui --grep @smoke                   # the ~1 minute subset
./run.sh local up --h2 && ./run.sh test --protocol ui --grep @smoke   # embedded-H2 stack
./run.sh test --protocol ui -b                              # after a Playwright bump or a ui.Dockerfile change
REPSY_UI_OPT_IN=throttle ./run.sh test --protocol ui        # also run an opt-in suite
```

The host needs Docker only: Chromium lives in the `ui` runner image (`runners/ui.Dockerfile`), never
on the host. **The base URL is the API port**: the SPA is served on 8080 (`REPSY_UI_BASE_URL`, falling
back to `REPSY_API_BASE_URL`), not on the protocol port 9090, where a deep link 404s. `src/` and
`tests/` are bind-mounted like for every runner, so editing a spec or a page object needs no rebuild;
only `runners/ui.Dockerfile` or `pnpm-lock.yaml` does (`-b`), because the browser build must equal
the locked `@playwright/test` version (the image installs it from `node_modules/.bin/playwright`, so
it follows the lockfile by itself).

**`REPSY_ADMIN_PASSWORD` must satisfy the panel's login form** (6-50 chars, a lower-case letter, an
upper-case letter and a digit, no whitespace) or UI login is impossible. The backend already refuses
to boot with a password that fails the complexity part but does not check the length, so the `ui`
project runs a worker-scoped preflight (`assertAdminCredentialsUsableInUi`) that fails every test with
a message saying exactly that. `e2e/.env.example` documents it next to the `REPSY_UI_*` variables.

| Variable              | Default                                            | Effect                                                                                  |
| --------------------- | -------------------------------------------------- | --------------------------------------------------------------------------------------- |
| `REPSY_UI_BASE_URL`   | `REPSY_API_BASE_URL`, else `http://localhost:8080` | Playwright `baseURL`                                                                    |
| `REPSY_UI_WORKERS`    | `4` (compose)                                      | Playwright `workers`; config-wide, so only the `ui` service sets it                     |
| `REPSY_UI_NO_SANDBOX` | unset                                              | `1` = `chromiumSandbox: false`, see "Chromium sandbox" below                            |
| `REPSY_UI_OPT_IN`     | unset                                              | comma list of opt-in suites; `optedIn('throttle')` in `src/ui/session.ts`               |
| `CI`                  | unset                                              | forwarded to the `ui` service only: `retries: 1`, `forbidOnly`, `trace: on-first-retry` |

Where things land (all under the existing bind mounts): `test-results/` holds, per failed test, the
trace (`trace.zip`; open it with `pnpm exec playwright show-trace <path>` on the host), the failure
screenshot and, for the built-in `page`, the video (`video: retain-on-failure`), plus
`test-results/junit.xml`; `playwright-report/` is the HTML report. Outside CI the trace mode is
`retain-on-failure` (`on-first-retry` would never fire with `retries: 0`); in CI it is
`on-first-retry`.

### Chromium sandbox

The sandbox stays **on**: `playwright.config.ts` sets `chromiumSandbox: true` explicitly (Playwright's
own default is `false`), never a bare `--no-sandbox` argument. As a non-root uid, Docker's default
seccomp profile blocks the user namespaces the sandbox needs ("Chromium sandboxing failed!"), so the
`ui` service runs with `security_opt: seccomp=./runners/ui-seccomp.json`, Playwright's own profile
(the default one plus exactly those syscalls; vendored from `microsoft/playwright`
`utils/docker/seccomp_profile.json`, reformatted for prettier). `ipc: host` gives Chromium a real
`/dev/shm` (Docker's 64 MB default crashes tabs under parallel workers). On a kernel or container
runtime that forbids unprivileged user namespaces even so, set `REPSY_UI_NO_SANDBOX=1` (in `.env` or
the shell); that is the only way to turn the sandbox off. Verified locally on Linux 7.0 with
`kernel.apparmor_restrict_unprivileged_userns=1` as a non-root uid (with the shipped profile the
sandbox works; with Docker's default profile it fails; `REPSY_UI_NO_SANDBOX=1` then passes); **not**
verified on a CI-hosted runner, which is the CI story's (RPS-1260) to check.

### Fixtures (`src/ui/fixtures.ts`)

`import { test, expect } from '../../src/ui/fixtures.js'`. It extends `scenarios/fixtures.ts`'s `test`,
so `panelApi` and `seeder` (per-test run id, cleanup) work unchanged, and adds:

| Fixture                               | What it is                                                                                                                                 |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ |
| `page` / `context`                    | Playwright's built-ins, with the flake defaults applied (below). Anonymous.                                                                |
| `adminPage`                           | the built-in `page`, already logged in as the harness admin before its first navigation. **Refuses** a test tagged `@credentials`.         |
| `adminSession`                        | the `{ username, token, refreshToken }` behind `adminPage`, one fresh API login per test                                                   |
| `seededUser`                          | a USER created by `seeder` for this test                                                                                                   |
| `userPage`                            | a second `BrowserContext`, logged in as `seededUser`. No video (Playwright only records its own default context); trace and screenshot yes |
| `openUiPage({ session?, viewport? })` | opens more contexts (a second admin, a mobile viewport); all are closed after the test, before `seeder` cleans up                          |
| `uiPreflight`                         | automatic, per worker: the admin-password check above                                                                                      |

- **Session seeding.** A logged-in page gets the three `localStorage` keys the SPA reads (`username`,
  `token`, `refresh-token`) from a context init script (`seedSession` in `src/ui/session.ts`): no
  `storageState` file, because tokens are per run and the SPA's refresh flow rewrites them. The write
  is **one-shot per tab** (a `sessionStorage` flag, plus an origin check), so a reload after logout
  stays logged out and a token the SPA has refreshed is not overwritten with the stale one. Login is
  **per test, never per worker**: refresh tokens are single-use with family revocation on reuse, so a
  token pair shared by a worker's tests is revoked the moment the first of them refreshes it.
- **Admin guard.** The harness admin is what the stack and every later test logs in with. Tests that
  change a password or username, or delete an account, are tagged `@credentials` and use `userPage`;
  `adminPage` throws for them. Page-object methods that change credentials must also call
  `assertNotAdmin(username)` / `assertPageNotAdmin(page)` (`session.ts`) before acting.
- **Flake defaults** (`src/ui/defaults.ts`, applied to every context): `reducedMotion: 'reduce'`
  (the app itself ignores it) plus an injected stylesheet that sets animation-duration to `1ms` and
  transitions to `0s` (`1ms`, not `animation: none`: Angular's `animate.enter`/`animate.leave` wait for
  `animationend`); and an **allow-list** for network access: any http(s) request whose origin is not
  the UI, API or repo base URL is aborted (Google Tag Manager, gtag, the Font Awesome CDN and Gravatar
  today), so runs are offline-safe. A test's own `page.route()` mock still wins over it.
- **Guards redirect to `/`, not `/login`.** `AuthGuard` sends an anonymous visitor of a protected route
  to `/`, and `/` renders the login form _in place_ (`AuthRedirectComponent` picks `LoginComponent` or
  the dashboard from the session), so the URL stays `/`. Only logout and a direct visit navigate to
  `/login`. Assert the login form is visible, not a `/login` URL, for a guard redirect.
- **Timing facts a test must respect.** `PanelLayoutComponent` hides `<router-outlet>` for a fixed
  500 ms after load, and a splash screen covers it: never assert "navigation finished", wait for the
  element or response that drives the view (`Shell.waitForView`, `expect(...).toBeVisible()`); there
  are no fixed sleeps (`eslint-plugin-playwright` errors on `waitForTimeout`). Toasts live 3 s and at
  most 3 are kept: assert a toast right after the action. The header "Profile" link is a raw relative
  `href` (a full reload, and from a nested route it resolves under the repo): state does not survive it.
- **Opt-in suites** (`@throttle`, `@scanner`, ...) skip themselves with
  `test.skip(!optedIn('throttle'), 'set REPSY_UI_OPT_IN=throttle')`, never through a `grepInvert` in
  the config.
- **Isolation.** Tests are `fullyParallel`, use their own `e2e-<runid>...` names, and never touch
  `admin` or the 9 default repos. Entities the **UI** creates are named with `seeder.reserveRepoName(type)`
  / `seeder.reserveUsername()` (the next unique name from the same counters `createRepo`/`createUser`
  use, nothing created) and then tracked with `seeder.adoptRepo(name)` / `adoptUser(id)` /
  `await adoptUserByUsername(name)` so `cleanup()` deletes them; a user renamed later is tracked by id,
  and the new name must also come from `reserveUsername()` so it keeps the `e2e-` prefix `sweep.ts`
  finds. Global counts (dashboard cards) are asserted against a same-moment API read.

### Page objects (`src/ui/pages/`)

Selectors are `data-testid` first (see "UI test ids" at the end of this file), `getByRole`/label
second, never CSS classes or visible text alone: every list renders a desktop grid and a mobile card
list at once, and strict mode counts hidden matches too.

| File            | Exports                                                                                                                                                                                                                        |
| --------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `base.ts`       | `UiPage` (`page`, `tid()`, abstract `goto()`)                                                                                                                                                                                  |
| `components.ts` | `Toasts` (`success()/error()/expectSuccess()/expectError()`), `DangerModal`, `Pagination`, `EmptyList`, `Spinner`, `DesktopList(page, 'repo')` (desktop container, `row(key)`, `rows()`, `inRow(key, id)`, `openRowMenu(key)`) |
| `shell.ts`      | `Shell` (`sidebar`, `mobileSidebar`, `header`, `toasts`, `dangerModal`, `openAvatarMenu()`, `logoutViaSidebar()`, `logoutViaHeader()`, `waitForView()`)                                                                        |
| `login.ts`      | `LoginPage` (`goto()`, `login(u, p)`, `error(field, validator)`)                                                                                                                                                               |
| `dashboard.ts`  | `DashboardPage` (welcome card only; RPS-1252 extends it)                                                                                                                                                                       |

**Shared files.** `playwright.config.ts`, `docker-compose.runners.yml`, `runners/ui.*`, `run.sh`,
`.env.example`, `src/ui/{fixtures,session,defaults}.ts` and `src/ui/pages/{base,components,shell,login}.ts`
belong to RPS-1250; a later story does not edit them. Need another fixture? Create
`src/ui/<area>-fixtures.ts` with `export const test = uiTest.extend<...>({...})` on top of
`src/ui/fixtures.ts` and import that in your specs. Need a shell or component helper that is missing?
Compose it inside your own page object (or a subclass) and list it in the PR description so the owner
can promote it. Need another viewport? `test.use({ viewport: { width: 390, height: 844 } })` in the
spec, or `openUiPage({ viewport })`; never a new Playwright project. Every later story replaces only its
own stub below, so the unchanged heading lines keep git's hunks apart; the Layout tree is not edited.

### Auth, guards and session (RPS-1251)

`tests/ui/auth/{login,guards,session}.spec.ts` (AUTH-01..11). Run them with
`./run.sh test --protocol ui --grep AUTH-`. UI login is typed ONLY in these specs; every other UI suite
logs in through the API fixtures. No test changes the admin or its password: the admin only types its
own credentials (AUTH-01), and negative logins use a seeded user or a name that does not exist.
`src/ui/pages/login-validation.ts` (composed on `LoginPage`) holds the validation helpers and the
visible message texts; `tests/ui/auth/stored-session.ts` reads the three `localStorage` keys.

| Spec      | Scenarios | What is pinned                                                                                                                                                                                         |
| --------- | --------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `login`   | 01-04, 11 | valid login and its 3 storage keys; 401 toast `Username or password is incorrect.` (wrong password and unknown user alike); every client-side rule with its message; the eye toggle; throttle (opt-in) |
| `guards`  | 05-07     | anonymous visit of `/repositories`, `/users`, `/security`, `/profile`, `/<repo>` shows the login form at `/`; `/login` bounces a logged-in user; a USER is sent to `/` from `/users` and `/security`   |
| `session` | 08-10     | expired access token is refreshed transparently; a refused refresh token logs out; sidebar and header logout clear the session                                                                         |

Things a later author must know:

- **The 401 of AUTH-08/09 is stubbed, everything after it is real.** An access token lives 30 minutes
  (not configurable) and only an _expired_ one is answered `sessionExpired`, the one answer that makes
  `RefreshTokenInterceptor` refresh; a token with a bad signature is answered `accessNotAllowed`, which
  it ignores. `expireAccessToken()` (`session.spec.ts`) answers calls carrying one given token with that
  401 (never the `/api/auth/` calls); the refresh, the rotation and the logout run on the real backend.
  The AUTH-09 cases: a refresh token that is garbage, one that was already used (single use), and a
  stubbed `refreshTokenExpired` answer.
- **The SPA reads `localStorage` once, at boot** (`AuthService`), and `seedSession()` writes once per
  tab: change the storage, then `reload()`; the change survives it. Two tokens minted in the same second
  are byte-identical, so compare a refreshed access token with a value the test wrote, not with the old one.
- **The password eye button is only a Font Awesome glyph**, and the font is a CDN resource the harness
  blocks, so the button has no size and Playwright calls it "not visible": use
  `LoginValidation.togglePasswordVisibility()` (a DOM click).
- **Inline validation messages appear on blur** (`touched`), one at a time, in the order required,
  pattern, minlength, maxlength; `LoginValidation.enter()` types and blurs.
- **AUTH-11 (`@throttle`) is skipped by default.** It needs a stack whose `AUTH_THROTTLE_MAX_FAILURES` is
  below 30 (the harness stack raises it to 100000, see `docker-compose.stack.yml`) and, once it trips,
  the client stays refused for the window (`AUTH_THROTTLE_WINDOW_SECONDS`), so run it alone, on a
  throwaway stack: `AUTH_THROTTLE_MAX_FAILURES=20` in the `repsy` service environment, then
  `REPSY_UI_OPT_IN=throttle ./run.sh test --protocol ui --grep AUTH-11`. Not run by CI or by default.
- **Known product bugs are `test.fail(true, ...)`**, written for the intended behaviour so the test
  turns red (and tells you to remove the line) when the bug is fixed: logging in from the in-place form
  a guard redirect shows (the URL is `/`, and `LoginComponent` navigates to `/` again) stores the session
  but does not render the dashboard until a reload; and a tampered (not expired) access token is never
  refreshed or logged out, the dashboard just stays empty.

### Repositories and dashboard (RPS-1252)

The dashboard (`/`), the repository list (`/repositories`) and the create-repository modal, which both
pages open. Files: `src/ui/pages/{dashboard,repositories,repo-create-modal}.ts`, `src/ui/repo-types.ts`
(the nine-row type table; a new protocol needs one row) and `tests/ui/{dashboard,repositories}/*.spec.ts`.
`./run.sh test --protocol ui --grep "DASH-|REPO-"` runs it; the P0 cases (DASH-01, REPO-01 for maven,
npm and docker, REPO-06, REPO-10) are also `@smoke`.

| Spec                           | Scenarios                                                                                                                                            |
| ------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `dashboard/dashboard.spec.ts`  | DASH-01 cards and counts against the API, DASH-02 Recent Activity, DASH-03 count row -> filtered list, DASH-04 USER                                  |
| `repositories/create.spec.ts`  | REPO-01 (one case per row of `UI_REPO_TYPES`, plus public+description, default type, from the dashboard), REPO-02 validation, REPO-03 duplicate name |
| `repositories/list.spec.ts`    | REPO-04 search, type selector and refresh, REPO-05 pagination, REPO-08 empty state, REPO-09 USER                                                     |
| `repositories/delete.spec.ts`  | REPO-06 delete, REPO-07 cancel                                                                                                                       |
| `repositories/routing.spec.ts` | REPO-10 `/<unknown>` is the 404 page, `/<repo>` opens the repository                                                                                 |

Things a test here relies on, which a change to the page can break:

- **The list loads nine `getInfo` calls, not one.** It shows the spinner until an answer has rows (or the last one is in) and renders
  rows as the others arrive; search, type filter and pagination run client-side over what has arrived. So
  `RepositoriesPage.afterInfoResponses()` (used by `goto`, `selectType`, `refresh`, `confirmDelete`) waits
  for every answer of the reload, then `settle()`s (two animation frames, so a negative assertion does not
  run on the view before the last answer). Type into the search box only after that.
- **Other tests' repositories are in the same list.** The list holds the nine defaults and everything the
  parallel workers created, ten per page, newest first. A test narrows the list with a string only its own
  repositories contain (`e2e-<runid>-`, `seeder.runId`) before it looks at rows, and asserts an unfiltered
  list by size only. Dashboard counts are compared with `panelApi.listRepos()` reads taken before AND after
  the page loaded, retried until nothing moved; Recent Activity (the six newest repositories) is reloaded
  until the seeded repository is in it.
- **UI-created repositories** are named with `seeder.reserveRepoName(type)` and adopted with
  `seeder.adoptRepo(name)` BEFORE the submit, so a failure half way still deletes them.
- **The visibility toggle** is toggled by clicking its label text: the `toggle-input` checkbox is `sr-only`
  under a covering span, so Playwright refuses to click it (read `isChecked()` from it, though).
- **Known defects, pinned as `test.fail`** (a `✘` line in the list reporter with a passing summary is the
  expectation): the description textarea's `maxlength="500"` hides the ">500" error (RPS-1265). Drop the
  `test.fail` when the fix lands. (The search box and page index after a refresh or a new search were
  pinned to RPS-1283 and are fixed; a refresh during a load, RPS-1293, is covered by a route that holds
  the first maven answer. A USER's Recent Activity was pinned to RPS-1276 until that
  fix; the row now shows, so DASH-04 asserts it plainly.)

### Users and profile (RPS-1253)

Specs: `tests/ui/users/{users-create,users-edit-delete,users-reset-password,users-list}.spec.ts` and
`tests/ui/profile/profile.spec.ts`. Page objects: `src/ui/pages/{users,profile,one-time-secret-modal}.ts`
(`UsersPage` with its `UserCreateModal`/`UserEditModal`, `ProfilePage`, `OneTimeSecretModal` for the
"shown once" reset-password modal, parameterised by ids so a token modal can reuse it). Fixtures on top
of the foundation: `src/ui/users-fixtures.ts` (`usersPage`, and `trackUiUser(name)`, which registers a
user the UI is about to create so a failing test still cleans it up).

| Scenario | Where                                                                                                                          |
| -------- | ------------------------------------------------------------------------------------------------------------------------------ |
| USR-01   | create a USER and an Admin, log in as each from a fresh context (an Admin sees Users, a USER does not), cancel resets the form |
| USR-02   | one test per validator of the create form, the message texts, a valid form, a duplicate username                               |
| USR-03   | rename, promote, demote next to another admin, the last-admin warning and locked switch, edit validation, taken name, cancel   |
| USR-04   | reset password: one-time modal, the new password logs in, the old one is refused, cancel resets nothing                        |
| USR-05   | delete (cancel, then confirm), delete next to another admin, the last-admin toast                                              |
| USR-06   | 11 users: search (incl. case-insensitive, no match), pagination both ways, refresh                                             |
| PRO-01   | change password: mismatch, cancel, confirm, re-login with the new one, the old one refused; field validation                   |
| PRO-02   | change username: reload as the new name, same account, repo protocol URL and repo page still work; validation; taken name      |
| PRO-03   | delete account: cancel, confirm, logged out, login refused                                                                     |

Rules these specs follow (and a later spec on these pages should too):

- **Credentials.** `profile.spec.ts` tests change or delete the account they are logged in as: they use
  `userPage` (a seeded USER) and are tagged `@credentials`, and `ProfilePage.submit*`/`requestAccountDeletion`
  refuse the harness admin. USR-04/05 act as the admin on SEEDED users (`adminPage`, not tagged: the
  admin's own credentials are untouched); `UsersPage.clickDelete`/`clickResetPassword` refuse the admin's row.
  The suite never edits, demotes or deletes the harness admin. Names a test creates or renames to come from
  `seeder.reserveUsername()`, so the `e2e-` prefix survives and sweep finds them; a renamed user is
  cleaned up by id.
- **Last admin.** The panel decides "last admin" on the client from the admins in the page it shows
  (RPS-1246); the server guards the real one, which the backend ITs cover. A shared stack always has the
  harness admin, so the real last-admin state is unreachable here. What is reachable: search for a seeded
  admin's exact username, and the view holds a single admin. Those tests pin what the panel does there
  today, and two `test.fail(... RPS-1246)` tests assert what it should do. Two admins in one view (search
  for `seeder.runId`, which is in both names) is the "not the last" case.
- **Search first.** The list is server-paged (10, newest first) and server-searched (case-insensitive
  substring), and other tests add users, so every list view is a search for a username or for
  `seeder.runId`, which is in exactly the names this test seeded (with 10 or more users, `-user-1` also
  matches `-user-10`: search the run id, not a name). After an edit or a delete the panel reloads without
  the search (and empties the box), so a test that checks a row afterwards searches for it again.
- **Toggle.** Click the `toggle` label (`UserCreateModal.roleToggle`), assert on `toggle-input`
  (`roleSwitch`): a click on the sr-only input is intercepted by the slider.
- **Eye buttons** (show/hide password) are Font Awesome glyphs, and the network allow-list blocks the
  Font Awesome CDN, so the buttons have no box: they are activated with `dispatchEvent('click')`.
- **Timing.** The username change ends in `location.reload()` in the tick that raises its toast, so that
  toast is not observable: assert the reload (`ProfilePage.changeUsername`) and the outcome.
- **Known bugs, pinned with `test.fail`**: RPS-1261 (create-user messages start with mojibake `â€¢`),
  RPS-1246 (last-admin check counts one page). The spec text of a `test.fail` states the key.

### Repository settings and deploy tokens (RPS-1254)

`/:repo/settings` and the deploy-token modals: SET-01..09 and TOK-01..05 of RPS-1254 (34 tests,
because the section-per-repo-type check and the toggle check run once per type). Run them with
`./run.sh test --protocol ui --grep @settings`; the P0 four (SET-01, SET-02, SET-06, TOK-01) are also
`@smoke`.

| File                                           | What it is                                                                                                                                |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| `src/ui/pages/repo-settings/page.ts`           | `RepoSettingsPage(page, repoName)`: `goto()`, `reload()`, `shell`, and one member per section                                             |
| `src/ui/pages/repo-settings/toggles.ts`        | `VisibilitySection`, `PackageOverrideSection` (`flip()`, `expectChecked()`), `VersionAllowanceSection` (`choose()`, `options()`)          |
| `src/ui/pages/repo-settings/deploy-tokens.ts`  | `DeployTokensSection`: `row(name)`, `cell()`, `rotateButton()/revokeButton()`, `rowNames()`, pagination, `createModal`, `infoModal`       |
| `src/ui/pages/deploy-token-modals.ts`          | `TokenCreateModal` (`create()`, field and per-validator error locators) and `TokenInfoModal` (`values()`, copy buttons)                   |
| `src/ui/pages/repo-settings/pgp.ts`            | `PgpSection`: selector, add, per-host rows and delete, built-in servers                                                                   |
| `src/ui/pages/repo-settings/repo-info.ts`      | `RepoInfoSection`: rename input/submit/errors, description save/reset                                                                     |
| `src/ui/pages/repo-settings/danger-zone.ts`    | `StorageSection`, `OrphanLayersSection`, `DeleteRepoSection`                                                                              |
| `src/ui/pages/repo-settings/readback.ts`       | `RepoSettingsReadback` (permissions/description, disk usage, key stores, allowed key servers) and `repoRootStatus()`, the repo-PORT probe |
| `tests/ui/settings/access-and-toggles.spec.ts` | SET-01 (access), SET-02 (visibility), SET-03 (override, sections per repo type), SET-04 (version allowance)                               |
| `tests/ui/settings/repo-management.spec.ts`    | SET-05 (rename, description), SET-06 (delete), SET-07 (orphan layers), SET-09 (storage)                                                   |
| `tests/ui/settings/pgp.spec.ts`                | SET-08 (Maven PGP key stores)                                                                                                             |
| `tests/ui/settings/deploy-tokens.spec.ts`      | TOK-01..05                                                                                                                                |

How the tests are written, and what they had to work around:

- **Persistence is asserted through the API.** The toggles and the selector PUT immediately and ask
  for no confirmation, so each test reads the setting back (`panelApi.getSettings`, tokens through
  `listDeployTokens`, description/usage/key stores through `RepoSettingsReadback`, which uses the
  test's `adminSession` bearer token because `PanelApi` does not wrap those reads) and again after a
  reload. Nothing sleeps: `expect.poll` on the API, `expect(...)` on the page.
- **Visibility is proven on the repo port too.** SET-02 checks `GET /<repo>/` on the PROTOCOL port
  (`REPSY_REPO_BASE_URL`, not the SPA port): 401 while private, 200 anonymous once public.
  TOK-03/TOK-04 do the same with a token (`repoRootStatus`, raw `fetch` and Basic auth, no package
  manager): a UI-created token gives 200, a revoked or rotated-away one 401.
- **Scope every locator to a section or modal.** `#name`, `#username` and `#description` exist twice
  on the page (the rename/description form and the create-token modal); only the `token-create-*`
  and `settings-*` ids are used, never a label or `#id`.
- **Toggles are flipped through their label** (`toggle-label`): the `role="switch"` checkbox is
  `sr-only` and covered by the drawn switch, so Playwright refuses to click it as "intercepted".
- **A forced click for Orphan Layers.** Every settings section is `mt-[-100px] pt-[100px]` (an anchor
  offset), so the Delete Repository section's transparent padding overlaps the lower part of the
  Orphan Layers button and Playwright's hit-target check never clicks it. `OrphanLayersSection.delete()`
  uses `click({ force: true })`, which lands on the button's own label like a real mouse.
- **The token "show" eye is clicked by event.** Its icon is a Font Awesome glyph from a CDN that the
  UI suite blocks (`src/ui/defaults.ts`), so the button has no size; `toggleTokenVisibility()`
  dispatches the click and the test asserts `aria-pressed` and the input's `type`.
- **Long names are truncated** to 10 characters plus `...` in the token list (the full text is in the
  tooltip popup on hover): `expectCellText()` hovers first; rows are keyed by the raw name through
  `token-row-<name>`.
- **Expiry colours.** The UI can only create a token between tomorrow and a year out, so TOK-02 makes
  the "within 7 days" token in the UI (today + 3 days, UTC, the form's zone) and seeds the already
  expired one and a far-off one through the API (`seeder.createToken`, which accepts a past date).
- **Clipboard.** TOK-01 grants `clipboard-read`/`clipboard-write` to the context and compares
  `navigator.clipboard.readText()` with the token and username, and asserts the button's
  `data-copied`.
- **Sections per repo type** (SET-03) is a table (`SECTIONS_BY_TYPE`) checked once for each of the
  nine types with `toHaveCount`, so an absent section and a hidden one are told apart.

Known product bugs are pinned with `test.fail('... RPS-nnnn')`, so the test turns red the day the
bug is fixed and the marker has to go: the Visibility and Package Override help texts describe the
opposite of the toggle (RPS-1261, two tests), and `#name`/`#description` are duplicated between the
rename form and the create-token modal (RPS-1266). A third pin is RPS-1285: revoking the only token on page 2
fires two list requests and the empty page-2 answer can land last, leaving "Your list is empty" over three tokens (the test
slows that answer to make the order certain). Not covered here: the Vulnerability Scanning toggle
(hidden without a scanner, RPS-1259), the per-protocol "configure" modal behind a token row, the
`reservedName` rename error (it has no test id), the expiration-date range messages (no test id) and
the token-name `minLength` branch, which is unreachable (`required` already covers an empty name, RPS-1265).

### Package seeding and protocol page objects (RPS-1255)

The `ui` runner image has only Node and Chromium, so a package for a package page is **published over
raw HTTP with the in-code builders** of `src/clients/*-raw.ts` (fflate jar, npm publish document,
docker blob + manifest, PyPI wheel), never with `mvn`/`npm`/`docker`/`twine`. Publishing goes to the
**protocol port** (`env.repoBaseUrl`, 9090 in the default stack, 15090 in a slot) as the harness admin;
the panel port only serves the SPA. Everything lives inside a repo the test created with
`seeder.createRepo(...)`, so deleting the repo is the whole cleanup (`./run.sh sweep --dry-run` finds
no leftovers).

| File                                           | What it is                                                                                                                                           |
| ---------------------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/seed/packages.ts`                         | `SEEDERS: Record<PackageProtocol, PackageSeeder>` (all nine keys), `seedPackage`, `seedPackages`, `seedVersions`, `defaultPackageName`, `protocolOf` |
| `src/seed/packages/{maven,npm,docker,pypi}.ts` | the four seeders; `shared.ts` holds `expectPublished`, `DEFAULT_VERSION`, `defaultPackageName`                                                       |
| `src/ui/package-fixtures.ts`                   | `test` = `uiTest` + `seedPackage`, `seedPackages`, `seedVersions` fixtures bound to `seeder.runId`                                                   |
| `src/ui/pages/protocol.ts`                     | `ProtocolListPage`, `VersionsPage`, `VersionDetailPage`, `protocolPages(page, descriptor, repo)`, `DESCRIPTORS`                                      |
| `src/ui/pages/protocols/<proto>.ts`            | one descriptor per protocol (all nine); `types.ts` is their shape                                                                                    |
| `tests/ui/packages/seed-proof.spec.ts`         | the proof: seeding, the four descriptors' data (search, sort, both deletes, mobile, pagination) and the special routes                               |

```ts
import { expect, test } from '../../../src/ui/package-fixtures.js';
import { DESCRIPTORS, protocolPages } from '../../../src/ui/pages/protocol.js';

test('lists a seeded package', async ({ adminPage, seeder, seedPackage }) => {
  const repo = await seeder.createRepo(RepoType.NPM);
  const pkg = await seedPackage(repo); // SeededPackage: { protocol, repoName, name, version, extra }
  const pages = protocolPages(adminPage, DESCRIPTORS.npm, repo.name);
  const list = pages.list();
  await list.goto();
  await list.expectRow(pkg);
  const detail = await list.openRow(pkg); // where the descriptor says a row click goes
});
```

- **Seeding.** `seedPackage(repo, opts?)` publishes one version (`opts`: `name` = the full identity,
  `version` default `1.0.0`, `index` for the default name, npm `scoped: false`, helm `variant`);
  `seedPackages(repo, n)` publishes `n` distinct packages (pagination needs more than 10);
  `seedVersions(repo, [...])` publishes versions of one package in order. The protocol is the repo's own
  type. A seeder throws if the server refuses. All nine protocols have a seeder
  (`src/seed/packages/<proto>.ts`; the last five arrived with RPS-1257).
- **Identity.** `PackageRef.name` is the raw row key: maven `group:artifact` (default: one group per
  `index`, so deleting a group never takes a sibling), npm `@scope/name` or `name`, docker the image
  (`version` = the tag), golang the module path.
- **Descriptors are data.** `ProtocolDescriptor.levels` has `list`, `versions`, `detail` and, where the
  protocol has them, `sublist` (maven group, npm scope) and `manifests` (docker). A list level says its
  route (`path`, with any query string), `rowKey`, `search` (`placeholder` and the `term` that finds a
  row), `sort` option names, `pagination`, `mobileCards`, `rowDelete` (dialog title and toast),
  `rowOpens` (where a row click goes), `rowLinks` (in-row links to other levels) and `installBar`. The
  detail level says `installContains`, `repoUrlIn` (`install`, `none` or `snippet:<slug>`), the snippet
  slugs, extra ids, `readme` and `delete` (`landsOn`). Gaps are values (`search: null`,
  `pagination: false`, `mobileCards: false`), so one scenario template needs no protocol `if`.
  `lastVersionRemovesPackage` is `false` for docker. A value read from the Angular code but not yet run
  in a browser says `unverified` in the descriptor's comment (or is the literal `'unverified'`): the
  story that first runs that protocol confirms it, editing only its own `protocols/<proto>.ts`.
- **Page objects.** Rows are `role=button` divs, not links; use `openRow`/`openLink`. `row`, `card`,
  `inRow`, `search`/`searchFor`, `sortBy`, `openDeleteDialog`/`deleteRow` (which asserts the dialog title
  and the toast), `pagination`, `installBar*`; the detail page has `install`, `installText`, `copyButton`,
  `snippet(slug)`, `delete()`. `protocolPages(...).extraPath('browser')` is maven's file browser.
- **Facts the proof pinned.** A maven group-list Delete removes the whole GROUP. Group and npm list
  searches match the group / scope only (not `group:artifact` or `@scope/name`). The npm scope route
  segment has no `@`. The sort menu stays open after a choice. Docker's manifest row is keyed by the tag,
  and its last-tag delete leaves the image listed. Playwright's own click is refused by every detail
  page's Delete button (the page host is reported above it), so `VersionDetailPage` clicks it with
  `force`.
- **Not covered here.** The scenario templates live in RPS-1256 (maven, npm, docker, pypi) and RPS-1257
  (cargo, nuget, helm, golang, ruby).

### Package tests: Maven, npm, Docker, PyPI (RPS-1256)

`tests/ui/packages/{maven,npm,docker,pypi}.spec.ts`, each one line of the shared template plus the
protocol-only scenarios. The template is `src/ui/package-scenarios.ts`, the UI counterpart of
`scenarios/loop.ts`:

```ts
registerPackageScenarios(DESCRIPTORS.npm, {
  knownFailures: {
    '05-mobile-sublist': 'RPS-1262: mobile scope-list cards gate Delete on canWrite',
  },
});
```

It registers `PKG-<proto>-01..06` for whatever the descriptor (`pages/protocols/<proto>.ts`) says and
never asks which protocol it is: a missing feature (`search: null`, `sort: null`, `pagination: false`,
no `rowDelete`, no `sublist`) registers the scenario that asserts its absence, and where a protocol
differs the descriptor carries the value (`repoUrlIn`, `detail.delete.landsOn`,
`lastVersionRemovesPackage`, `configure`, `levels.sublist.siblingName`).

| ID  | What it does (`@packages`; `01` is also `@smoke`)                                                                                                                |
| --- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 01  | seeded package on the list; row -> versions -> detail; install snippet (+ repo URL where `repoUrlIn` says); the copy button and the clipboard                    |
| 02  | search a package (the descriptor's term), search a version, every sort option, 12 packages over two pages                                                        |
| 03  | a fresh repo shows the empty list                                                                                                                                |
| 04  | delete from the detail page (toast, landing page), the last version, a list row (cancel first), a versions row, a sublist row (Maven group page, npm scope page) |
| 05  | a USER sees no Settings, no row dropdown, no detail Delete; and no Delete on the mobile cards of each level (the admin is the control on the same page)          |
| 06  | Configure modal (repo name, `YOUR_PASSWORD` where the protocol has one) and the deploy-token variant opened from a token row in the settings                     |

`knownFailures` keys (`PackageScenarioKey`) run their step under `test.fail`, so a fix turns it red and
the title carries the reason. Pinned today: `05-mobile-*` (RPS-1262: npm scope list and version list,
PyPI list and version list gate the mobile Delete on `canWrite`), Maven `04-detail` (RPS-1296), and in the
specs Maven Gradle Groovy = Grape block (RPS-1261), Docker desktop manifest Digest/Config Digest cells
(RPS-1261), npm Bugs URL and Keywords (RPS-1261), PyPI "Pre release:" for a post release and the mobile
"Latest" link (RPS-1261), the Maven browser's Settings button for a USER (RPS-1262) and its first click
after a cold load (RPS-1297).

Facts the tests rely on (probed, RPS-1256):

- A row's dropdown opens over the NEXT row, whose `fade-in-down` class (`animation ... forwards`) is a
  stacking context of its own and paints above the menu: a mouse click on the middle of Delete lands
  on the next row. `ProtocolListPage.openDeleteDialog` therefore dispatches the button's `click`.
- The Maven browser's first directory click after a cold load does not descend (the permissions load
  twice and the second load empties the directory stack under an in-flight listing): `enterDirectory`
  in `maven.spec.ts` clicks until the breadcrumb shows.
- Maven's version detail of a non-latest version shows and deletes the LATEST version, so the shared
  delete scenario is pinned for Maven and PKG-maven-07 asserts the content separately.
- The Configure modal differs per protocol (`ProtocolDescriptor.configure`): Maven and PyPI print
  `YOUR_PASSWORD`, npm and Docker have no password placeholder; the token variant says
  `YOUR_DEPLOY_TOKEN` (Maven, npm), `<repsy_deploy_token>` (Docker), `your deploy token` (PyPI).
- The seeders publish no README or description, so `npm.spec.ts` (README, keywords, bugs URL) and
  `pypi.spec.ts` (long description, home page) publish their own rich package with the raw builders.
- "Version 'x' not found" exists on the Go detail page only; nothing asserts it here.

### Package tests: Cargo, NuGet, Helm, Go, Ruby (RPS-1257)

`tests/ui/packages/{cargo,nuget,helm,golang,ruby}.spec.ts`: each is one `registerPackageScenarios(...)`
call (PKG-<proto>-01..06, `01` is `@smoke`) plus the protocol's own PKG-<proto>-07 tests. All nine
protocols now have seeders, so `SEEDERS` has no `notImplemented` entry left. Every package is published
over raw HTTP to the protocol port as the admin, with a real artifact built in code (the `ui` image has
no toolchain):

| Seeder               | Wire request and artifact                                                                                                                                          |
| -------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `packages/cargo.ts`  | `PUT api/v1/crates/new` (length-prefixed JSON + `.crate`); `.crate` = gzip+ustar with `Cargo.toml` and `src/lib.rs`/`src/main.rs`; `publishCrate` adds README/deps |
| `packages/nuget.ts`  | `PUT v3/package/` multipart `package`; `.nupkg` = fflate zip with the nuspec                                                                                       |
| `packages/helm.ts`   | `variant: 'oci'` (default): config blob, chart blob, manifest under the tag; `variant: 'classic'`: multipart `chart` to `/<repo>/api/charts` (ChartMuseum)         |
| `packages/golang.ts` | `PUT <repo>/<module>/@v/<v>.zip` + `Content-Sha256`; a version without `v` gets one (`1.0.0` seeds `v1.0.0`)                                                       |
| `packages/ruby.ts`   | `POST api/v1/gems`; `.gem` = tar of `metadata.gz`, `data.tar.gz`, `checksums.yaml.gz`                                                                              |

Default names (`defaultPackageName`): Cargo and Ruby `e2e_<runid>_pkg_<n>` (underscores only: the panel
keys a crate by its normalised name, `-` becoming `_`), NuGet and Helm `e2e-<runid>-pkg-<n>`, Go
`e2e.repsy.test/e2e-<runid>-pkg-<n>`. The builders are the ones the protocol suites already use
(`clients/{cargo,nuget,helm,helm-chart,golang,ruby}-raw.ts`); the only edits there are
`buildPublishBody`'s optional `readme`/`deps` and `export` on Helm's two OCI body builders.

| ID        | What it does                                                                                                                                                                                                                                                                                             |
| --------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| cargo-07  | README renders (and is absent when none was published); deps in the Cargo.toml block; Add Dependency vs Install Binary; the four sorts (crates at different versions); latest version and every version; delete a crate with two versions; a yanked version still listed (the panel shows no yank state) |
| nuget-07  | stable and pre-release side by side; a stable-only repo (`releases`/`snapshots`) refuses a pre-release and keeps the list; unlist/relist flips `Listed` on the detail (the version stays listed); the four install snippets, no dependencies, nuspec metadata                                            |
| helm-07   | a chart published to each module (OCI and classic) in one list, both open with digest and Chart.yaml; one chart with versions from both modules; deleting a classic chart; the Latest link                                                                                                               |
| golang-07 | list -> `/modules?modulePath=` -> `/modules/version?modulePath=&version=` with the breadcrumb; deep link; GOPROXY endpoints; "Version 'x' not found"; the detail without its query goes to the list; a module path with slashes is searchable                                                            |
| ruby-07   | yanked badge on the versions list and on the detail after a yank through the API; install commands, platform and checksum; the Latest link                                                                                                                                                               |

What the descriptors record (found by running each protocol): a version row's link appends `#security`
(the template accepts a fragment); a detail Delete lands on the list (Cargo, Ruby), on the versions page
(NuGet, Helm, Go) and, for the LAST version, on the list for NuGet (`landsOnLast`) and on the empty
versions page for Helm and Go; deleting the last version removes the package for all but Go, whose module
stays listed with no versions (like Docker, RPS-1288 (5)); Cargo/NuGet/Helm/Ruby Configure texts have
`<YOUR_...>` placeholders and the same body in the deploy-token variant (`deployTokenMarker` is optional
now: absent = same body, only the title differs), Ruby's title is the same in both.

Pinned with `test.fail` / `knownFailures` (each still fails for the stated reason, checked un-pinned):

| Where                                                  | Bug                                                                                                                                                                                                                                           |
| ------------------------------------------------------ | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| cargo `05-mobile-versions`, nuget `05-mobile-versions` | RPS-1262 (2): the version list has no `lg:hidden` cards, a phone shows nothing                                                                                                                                                                |
| nuget `02-versions-search`                             | RPS-1262 (3): no search box on the version list                                                                                                                                                                                               |
| helm-07 twelve versions                                | RPS-1262 (3): no pager on the version list, all twelve render                                                                                                                                                                                 |
| golang-07 empty versions page                          | RPS-1262 (3): `<app-pagination>` renders under the empty state of an unknown module, printing "1 NaN"                                                                                                                                         |
| cargo-07 row menu real click                           | RPS-1299: the menu of a non-last row paints under the next row, Playwright's click is refused ("subtree intercepts pointer events")                                                                                                           |
| cargo-07 Newest by publish time                        | RPS-1301: Newest/Oldest order by `max_version` (a text column), not by when a crate was published; the seeder gives each crate its own version so the sort and pager have distinct keys (RPS-1298), and cargo-07 asserts the sorts by version |
| helm-07 deleting the last version                      | RPS-1302: the versions page of the deleted chart raises two error toasts, "Chart not found." and "[object Object]"                                                                                                                            |

`seed-proof.spec.ts` (RPS-1255) now covers all nine protocols; its generic search/sort/delete walk stays on
the first four (the other five have the protocol-aware version of it in PKG-<proto>-02 and -04).
RPS-1298 (a pager without a tie-breaker) is avoided as in the template, by seeding sequentially. The
mobile-Delete `canWrite` bug of RPS-1262 (1) does not exist in these five protocols (only PyPI and npm).

### Errors, navigation, mobile and accessibility (RPS-1258)

`tests/ui/{errors,nav,a11y}/*.spec.ts` (ERR-01..03, NAV-01..02, A11Y-01) plus `src/ui/a11y.ts` (the axe
helper) and `tests/ui/nav/breadcrumb.ts` (the breadcrumb page object). Run them with
`./run.sh test --protocol ui --grep "ERR-|NAV-|A11Y-"`. `@axe-core/playwright` is the only dependency
this story added (`package.json`, `pnpm-lock.yaml`), so the `ui` runner image must be rebuilt once
(`./run.sh test --protocol ui -b`).

| Spec              | Scenarios | What is pinned                                                                                                                                                                                                                                                                                                                                                         |
| ----------------- | --------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `errors/errors`   | ERR-01    | all nine `.../{TYPE}/info` calls answered 500: exactly `Server error` (never the body's text), no rows, page alive; ONE type (NPM) failing: the toast plus the `repo-warning`, others still list; all types failing: `repo-error`, not `empty-list`, and the refresh button retries; a failing NuGet package list: `pkg-error` with `Error Occurred` next to the toast |
| `errors/errors`   | ERR-02    | an aborted request (status 0): `Connection error`, on the repository list and on the users page                                                                                                                                                                                                                                                                        |
| `errors/errors`   | ERR-03    | 403 on `GET /api/users`: `Access denied` (no body) or the server's own `text`; 403 on `/security`: `Access denied` plus `You do not have permission to view this page`, and the redirect to the dashboard                                                                                                                                                              |
| `nav/breadcrumbs` | NAV-01    | Maven: version -> artifact -> group -> repository -> Repositories, URL, remaining crumbs and the rendered page after each click; npm scoped package: the `@scope` crumb over a URL without `@`                                                                                                                                                                         |
| `nav/mobile`      | NAV-02    | 390x844: desktop sidebar hidden and burger present (and the reverse at 1440); repository, users, Maven list/group/versions show `<page>-cards` and hide `<page>-table`; `test.fail`: the burger never opens the mobile sidebar (x2: admin flow, USER flow)                                                                                                             |
| `a11y/a11y`       | A11Y-01   | axe on login, dashboard, repository list, repository settings, users (admin); report-only                                                                                                                                                                                                                                                                              |

Things a later author must know:

- **Routes are stubs, everything else is real.** ERR tests answer one URL with `page.route`, remove it
  again in a `finally` (`withRoute`), and start asserting the toast BEFORE the navigation that raises it
  (`expectToastLater`): a toast lives 3 s. The interceptor's mapping is status 0 -> `Connection error`,
  403 -> the server's `text` or `Access denied`, >= 500 -> `Server error`, other 4xx -> the server's `text`.
- **The repository list renders whatever arrives** of its nine parallel `info` calls, so one failing type
  loses only its own rows and the page shows `repo-warning`; only when EVERY request failed does it show
  `repo-error` (never the empty state), and the refresh button retries.
- **The mobile sidebar cannot be opened.** `PanelLayoutComponent` renders `<app-panel-header />` without a
  `(mobileMenuToggle)` handler, so `isMobileMenuOpen` stays false. The two `test.fail` NAV-02 tests are
  written from the templates (open, link, X, backdrop, USER without Users/Security, logout); the steps after
  the burger have not run against a working sidebar and may need adjusting when it is fixed.
- **axe, report-only by default.** `scanPage()` (`src/ui/a11y.ts`) runs the WCAG 2.0/2.1 A and AA rules,
  attaches `axe-<page>.json` (summary + every violation with its nodes) and `axe-<page>.txt` to the report,
  writes the JSON to `test-results/<test>/axe-<page>.json` and prints one `AXE <page> [report]: ...` line, and
  never fails. `DEFAULT_A11Y_MODE` in `src/ui/a11y.ts` is the one flag that makes it fail on
  serious/critical violations (RPS-1266 flips it once the baseline is clean); for a single run use
  `REPSY_UI_OPT_IN=a11y-enforce` (or `a11y-report`), because `docker-compose.runners.yml` forwards that
  variable already. Font Awesome (a blocked CDN in this harness) icons render as empty boxes; each summary
  counts them as `faNodes` per rule so they stay separable. Axe cannot judge a modal or dropdown that is not
  open: the five scans are of the pages at rest.

Baseline on `main` (RPS-1266 tracks fixing it; serious/critical only, WCAG A/AA):

| Page                | Violations                                                                                                                                   |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- |
| login               | `button-name` (critical, 1): the password eye toggle                                                                                         |
| dashboard           | none                                                                                                                                         |
| repository list     | `button-name` (critical, 1): the refresh button; `nested-interactive` (serious, 1): a `role="button"` row containing a link and the row menu |
| repository settings | `button-name` (critical, 1): the PGP add button; `color-contrast` (serious, 2): the disabled keyserver rows                                  |
| users (admin)       | `color-contrast` (serious, 1): the `USER` role badge                                                                                         |

### Security scanning UI (RPS-1259)

Every piece of scanner UI (the badges on the repository, package and version lists, the scan section of
a version page, the Vulnerability Scanning toggle of the settings, the content of `/security`) is gated
by `GET /api/security/supported-repo-types`, which is `[]` in the e2e stack (`SECURITY_SCANNER=disabled`).
These specs therefore **stub the scanner-facing calls with `page.route`** and let everything else
(login, repositories, packages, settings) hit the real backend. They are tagged `@mocked`
(`./run.sh test --protocol ui --grep @mocked`; SEC-02a..e, 51 tests, no scanner, no extra
stack); one is also `@smoke`. The real-scanner half (a Trivy or stub scanner in the stack, SEC-01) is
RPS-1270.

| File                                           | What it is                                                                                                                                                                                                                                                                                                                                |
| ---------------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `src/ui/security-stubs.ts`                     | typed stubs: `stubSupportedRepoTypes`, `stubRepoSecuritySummary`, `stubArtifactSecuritySummary`, `stubVersionSecuritySummary`, `stubRepoSecurityDetail`, `stubArtifactSecurityDetail`, `stubVersionScans` (+ `ScanScript`), `stubSecurityScans`, `stubSecurityScansSummary`, builders `finding`, `findings`, `scanInfo`, `severityCounts` |
| `src/ui/security-fixtures.ts`                  | `test` = `package-fixtures` + an automatic teardown that `unrouteAll`s the page                                                                                                                                                                                                                                                           |
| `src/ui/pages/security.ts`                     | `SecurityPage` (`/security`), `ScanSection`, `SecurityModal`, `VulnerabilityScanningSection`, `SelectorControl`, `securityBadgeIn`, `badgeText`, `severityBadge`                                                                                                                                                                          |
| `tests/ui/security/cases.ts`                   | the four seedable protocols (maven, npm, pypi, docker) and where each shows its package badge                                                                                                                                                                                                                                             |
| `tests/ui/security/badges.spec.ts`             | SEC-02a: repo, package and version badges and their modals                                                                                                                                                                                                                                                                                |
| `tests/ui/security/scan-section.spec.ts`       | SEC-02b: the scan section, "See Security Details", Pending to Completed by polling, rescan, history, findings paging and sort                                                                                                                                                                                                             |
| `tests/ui/security/settings-toggle.spec.ts`    | SEC-02c: the settings toggle per repo type, the PUT body, persistence                                                                                                                                                                                                                                                                     |
| `tests/ui/security/security-page.spec.ts`      | SEC-02d: `/security` (distribution, filters, empty states, paging, row navigation)                                                                                                                                                                                                                                                        |
| `tests/ui/security/dashboard-overview.spec.ts` | SEC-02e: the dashboard's Security Overview                                                                                                                                                                                                                                                                                                |

How the stubs are typed, and the rules they follow:

- **Typed from the spec.** Every body is a `RestResponse*` model of `src/api/generated` (built from
  `openapi-spec.yaml` by `pnpm gen:api`) and every helper takes the generated models as parameters, so
  a renamed or retyped field breaks `tsc` instead of rendering nothing. Enums (`Severity`, `ScanStatus`,
  `RepoType`) come from the same models.
- **Register before navigating.** `SecurityScanSupportService` reads `supported-repo-types` once per SPA
  load; a list asks for its summary as soon as it renders. To flip an answer, stub again and `reload()`
  (the newest route for a URL wins). Stubs are idempotent (the answer depends on the request and on the
  state object the test passed in, never on a call count); a handle counts calls only for assertions.
- **The scanner is a script the test drives.** `ScanScript` holds one version's scans (newest first);
  `setStatus()` moves the newest one Pending, Queued, Running, Completed or Failed, `rescan()` adds one
  (what `POST .../scan` does). The scan section polls `GET /api/repos/{repo}/scans/{id}` every 3 s while a
  scan is unfinished, so a test flips a status and then waits, bounded (15 s), for the panel to show it:
  web-first assertions and `expect.poll`, never a fixed sleep. The overview's counters are the newest
  COMPLETED scan's, the scan's `status` is the newest scan's, and a scan reports findings only once it is
  COMPLETED (the contract in `openapi-spec.yaml`).
- **Two things are the stub's own assumptions**, not the backend's: `stubSecurityScans` filters
  `repoName` as a case-insensitive substring and orders newest first, and findings sort by severity rank
  (Critical first for `ASC`). What a test asserts about them is the request the panel sent
  (`handle.last().searchParams`) and the rows it drew.
- **What is real.** The repositories and packages (raw-HTTP seeded), the routes the rows navigate to (a
  `/security` row and a modal's recent-scan row must land on the page the protocol descriptor names, at
  `#security`), and, for SEC-02c, the settings: the PUT goes to the backend and the test reads the stored
  value back.
- **The sidebar Security link does not need a scanner**: it shows for every admin (`isAdmin` only), and
  `/security` then shows its empty states with a type filter that offers only `ALL`.

Known product defects, pinned with `test.fail` so the test turns red the day it is fixed and the marker
has to go (a `✘` in the list reporter with a passing summary is the expectation): three security-modal
defects (RPS-1295): the X of a repository or package modal
also opens the row it sits in (the modal is rendered inside the clickable row and only the backdrop and
the links stop the click), and with a chart the dialog is tall enough that the page header covers its
title and X at 1440x900.

## Running

```bash
./run.sh local up            # starts postgres:18 + Repsy (built from the repo root Dockerfile;
                              # set REPSY_IMAGE to test a published image instead)
./run.sh test                # runs the "skeleton" runner container against it
./run.sh test --protocol maven
./run.sh test --protocol npm
./run.sh test --protocol cargo
./run.sh test --protocol nuget
./run.sh test --protocol docker
./run.sh test --protocol helm   # runs BOTH Helm modes (OCI + classic/ChartMuseum) from one runner
./run.sh test --protocol pypi
./run.sh test --protocol golang
./run.sh test --protocol ruby
./run.sh test --protocol ui     # the panel UI suite in headless Chromium (see "UI suite")
./run.sh test --protocol skeleton,maven,npm,cargo,nuget,docker,helm,pypi,golang,ruby
./run.sh test --grep '@smoke'
./run.sh test -b             # rebuild the runner image(s) first (Dockerfile/lockfile changed)
./run.sh local down
./run.sh sweep               # deletes e2e-* leftovers older than 24h; --hours N or --all

./run.sh local up --h2       # starts the H2 profile instead: Repsy alone, embedded H2, no postgres
                              # (same ports, so stop the postgres profile first if it is up)
./run.sh test --protocol skeleton,maven,npm,cargo,nuget,docker,helm,pypi,golang,ruby --grep '@smoke'
./run.sh test --protocol maven   # one full catalog against H2 -- see "Stack profiles" above
./run.sh local down --h2
```

`run.sh test` accepts `--target local|remote|ci` and `--protocol a,b` (a comma-separated list of
runner services: `skeleton`, `maven`, `npm`, `cargo`, `nuget`, `docker`, `helm`, `pypi`, `golang`,
`ruby`, `ui`) — identically whichever stack profile is up (see "Stack profiles (postgres and H2)" above).
Reports land under `e2e/test-results/` (JUnit
XML) and
`e2e/playwright-report/` (HTML) — one `run.sh test` invocation covering several `--protocol` services
overwrites that JUnit file per service, so diff/compare a single protocol's run in isolation
(`--protocol maven` alone) rather than a combined one if you need its own report.

`run.sh` creates `e2e/test-results/` and `e2e/playwright-report/` itself, as the invoking user, before
any subcommand that starts a runner container (`test` and `sweep`; any new one must do the same via
`ensure_runner_dirs`). The runners bind-mount both directories, and Docker would otherwise create a
missing one as root, so the next runner (which runs as your uid) fails with `EACCES` writing its
reports. If you already have root-owned ones from an older checkout, remove them once
(`sudo rm -r test-results playwright-report`).

Editing a test, a file under `src/`, or the openapi spec needs no image rebuild: both are
bind-mounted into the runner container, which regenerates the API client on every start. Only a
change to `runners/base.Dockerfile` or `pnpm-lock.yaml` needs `-b`.

The runner container runs as the invoking host user, not root (`docker-compose.runners.yml`'s
`user:`, fed by `run.sh`'s `HOST_UID`/`HOST_GID`), so the regenerated API client and test reports
come out owned by that user on the host, not root. `entrypoint.sh` calls the installed binaries
directly (`node_modules/.bin/...`) rather than through `pnpm run`/`pnpm exec`: pnpm's script runner
re-verifies `node_modules` against its store on every invocation, which fails under that non-root,
host-matching uid even though the packages themselves only need to be read.

## Panel API facts this step verified against a running instance

- `POST /api/auth/login` → `{ data: { token, refreshToken } }`; every other call in `panel-api.ts`
  sends `Authorization: Bearer <token>`. The openapi spec only lists `Authorization` as an explicit
  parameter for `user-controller` routes; `protocol-repo-controller` and
  `protocol-deploy-token-controller` routes need it too, just via an argument resolver the spec
  does not document.
- `POST /api/users`, `DELETE /api/users/{userId}`, `GET /api/users` (ADMIN only).
- `POST /api/repos/{repoType}` (`RepoType` is upper-case: `MAVEN`, `NPM`, ...), `DELETE
/api/repos/{repoName}`, `GET /api/repos/{repoType}/info` (list), `GET`/`PUT
/api/repos/{repoName}/settings`.
- `POST /api/repos/{repoName}/deploy-tokens` (`name`, `read_only`, `expiration_date`, `username`),
  `DELETE .../deploy-tokens/{tokenId}`, `PUT .../deploy-tokens/{tokenId}` (rotate), `GET
.../deploy-tokens` (the create response has no token id; `seeder.ts` looks it up by name right
  after creating it).
- A **past `expiration_date` is accepted** — `DeployTokenService.createDeployToken` does not
  reject it — which is how `seeder.ts` creates an already-expired token.
- Deleting an already-deleted repo or user answers `404` (`repoNotFound`/`userNotFound`); `Seeder.
cleanup()` tolerates exactly that status.
- On the repo port, a `GET` on a private repo's root with **Basic** auth (any username, the deploy
  token as the password): a **valid read-write token answers `200`**; an **expired token answers
  `401`** (`deployTokenExpired`). Both were probed against a running instance before being pinned
  in `tests/skeleton/seed.spec.ts`.

## Verification

```bash
cd e2e
pnpm install
pnpm gen:api
pnpm lint
pnpm exec tsc --noEmit
pnpm exec prettier --check .
```

```bash
./run.sh local up
./run.sh test --protocol maven
./run.sh test --protocol maven   # again, without resetting the stack — proves run isolation
./run.sh test --protocol npm
./run.sh test --protocol npm     # again — proves run isolation for npm too
./run.sh test --protocol cargo
./run.sh test --protocol cargo   # again — proves run isolation for cargo too
./run.sh test --protocol nuget
./run.sh test --protocol nuget   # again — proves run isolation for nuget too
./run.sh test --protocol docker -b  # -b the first time: builds the docker runner image
./run.sh test --protocol docker  # again — proves run isolation for docker too
./run.sh test --protocol helm -b    # -b the first time: builds the helm runner image
./run.sh test --protocol helm    # again — proves run isolation for helm too (both modes)
./run.sh test --protocol pypi
./run.sh test --protocol pypi    # again — proves run isolation for pypi too
./run.sh test --protocol golang -b  # -b the first time: builds the golang runner image
./run.sh test --protocol golang  # again — proves run isolation for golang too (H15)
./run.sh test --protocol ruby -b    # -b the first time: builds the ruby runner image
./run.sh test --protocol ruby    # again — proves run isolation for ruby too (H15)
./run.sh test --protocol maven,npm,cargo,nuget,docker,helm,pypi,golang -b  # regression: every protocol before ruby stays green
./run.sh test                    # the skeleton project
./run.sh sweep --dry-run         # before tearing down: confirms nothing was left behind
./run.sh local down
```

After a run, confirm no `e2e-*` repos or users remain: `GET /api/repos/{repoType}/info` for every
`RepoType` and `GET /api/users` should list none (`./run.sh sweep --all --dry-run` does this for
you). A meaningfulness check for the maven catalog: temporarily flip one scenario's expectation in
`catalog.ts` (e.g. `token-expired`'s publish to `'ok'`, or `redeploy-snapshots-off`'s), confirm
`./run.sh test --protocol maven --grep <id>` fails, then restore it (this was also re-run once for
the docker protocol specifically, flipping `token-expired`'s `publish` to `'ok'`: `./run.sh test
--protocol docker --grep token-expired` failed as expected, with `crane push`'s own raw-probe status
`401` reported against the flipped `'ok'` expectation; and once for helm, adding a temporary
`expectByProtocol: { helm: { publish: 'ok' } }` to `token-expired` — `./run.sh test --protocol helm
--grep "helm > token-expired"` failed as expected, `helm push`'s own raw-probe status `401` against
the flipped `'ok'` expectation, then reverted; and once for golang, adding a temporary
`expectByProtocol: { golang: { publish: 'ok' } }` to `token-expired` — `./run.sh test --protocol
golang --grep token-expired` failed as expected (`Error: expected "ok", got "unauthorized" (http 401;
curl/go exit 22; curl ... -u <token>:*** -T module.zip ...)`), then reverted, `git diff` confirmed
clean). `./run.sh sweep --dry-run` lists any `e2e-*` leftovers without deleting them.

The protocol suites carry exactly one `test.fail` pin left (checked on a stack built from `main` at
RPS-1287): docker's R7/B2 (RPS-1216, overriding a tag makes the previous manifest unpullable by
digest, still open), so a docker run prints one `✘` line next to an overall "passed"/exit `0` —
treat those two as authoritative over the per-line glyph. Every other suite (maven, npm, cargo,
nuget, helm, pypi, golang, ruby) has none: the pins for RPS-1205/1212/1215/1220/1221-1225 and the
RPS-1124 storage-order family (cargo, pypi, ...) were flipped into plain assertions as their fixes
landed, and a `test.fail` whose bug is fixed makes the runner exit `1` with "Expected to fail, but
passed", so it has to be flipped as part of the fix.

### H2 profile verification (this step)

```bash
./run.sh local up                # postgres profile: unaffected by this step
./run.sh test --protocol maven   # 45 passed -- regression check, byte-for-byte same as before this step
./run.sh local down
./run.sh local up --h2
./run.sh test --protocol skeleton,maven,npm,cargo,nuget,docker,helm,pypi,golang,ruby --grep '@smoke' -b
./run.sh test --protocol maven   # one full catalog on H2: 45 passed
./run.sh test --protocol maven   # again, no stack reset: 45 passed -- proves run isolation on H2 too
./run.sh sweep --dry-run         # "sweep: deleted 0 repo(s) and 0 user(s)" -- nothing left behind
./run.sh local down --h2
./run.sh local up --h2 && ./run.sh local down --h2   # confirms a second up starts empty
```

All of the above ran clean (exit `0`) against the locally built `repsy-os-e2e:local` image. The
`--grep '@smoke'` run across all 10 runners reported 38 passed total (skeleton 2, maven 4, npm 2,
cargo 2, nuget 3, docker 4, helm 5, pypi 4, golang 6, ruby 6), including the same `test.fail`-routed
tests reporting a per-line `✘` with an overall "passed"/`0` (npm's RPS-1205 real-client test, cargo's
hyphenated-crate-name test, helm's HL1/HL2) documented above for the postgres profile — parity
confirmed between the two database profiles for every runner, not just maven.

The fresh-DB-per-`up` claim was confirmed directly, not just inferred: after the second `local up
--h2` above, `GET /api/repos/MAVEN/info` returned exactly the one default `maven` repo (fresh
`createdAt`, `diskUsage: 0`) and `GET /api/users` returned exactly the one `admin` user — no
leftovers from the runs immediately before it.

## UI test ids (data-testid conventions)

The Playwright UI suite (RPS-1248) selects panel elements by `data-testid`, added to the Angular
templates by RPS-1249. Why: before it exactly one id existed (`readme`); native ids are duplicated
(`username` x5, `name` x3, `description` x3); every list renders a desktop grid and a mobile card
list at once, so text and role locators match twice; and Tailwind classes change with every restyle.
Selector priority: `getByTestId` first, then `getByRole`/`getByLabel`, never CSS classes.

### Rules

1. Format `<page>-<element>`, kebab-case. Static: `data-testid="x"`. Dynamic:
   `[attr.data-testid]="'x-' + key"` (never `[data-testid]`, never `data-testid="x-{{ key }}"`; both
   fail to compile). Never on `<ng-container>`, `<ng-template>` or control-flow blocks.
2. Row keys are the raw identity (repo name, username, version, `@scope/name`, `group:artifact`,
   `golang.org/x/mod`): unmodified, so they can contain `@ / : .` and upper case. `getByTestId`
   matches the exact string.
3. Desktop list container `<page>-table`, row `<page>-row-<key>`; mobile container `<page>-cards`,
   card `<page>-card-<key>`. `-row-` and `-card-` differ on purpose, so `getByTestId('repo-row-x')`
   never hits the hidden mobile duplicate. The row/card id sits on the clickable element.
4. Elements inside a row or card use short page-independent ids (`row-name`, `row-menu`,
   `row-delete`, ...). They repeat per row: always scope them,
   `page.getByTestId('repo-row-x').getByTestId('row-delete')`.
5. Shared components carry fixed internal ids (table below). To tell two instances apart, the usage
   site puts a static `data-testid` on the component host (`<app-searchbox data-testid="repo-search">`)
   and the page object chains `getByTestId('repo-search').getByTestId('search-input')`. Never add an
   `@Input() testId`.
6. Validation messages: `<form>-<field>-error-<validator>`, validator names as Angular reports them
   (`required`, `minlength`, `maxlength`, `pattern`; `mismatch` for confirm-password checks). Key by
   validator, never by text.
7. Error branch: `<page>-error` on the wrapper, `<page>-error-message` on the message. Custom empty
   state: `<page>-empty`; `<app-empty-list>` is the shared `empty-list`.
8. `data-testid="readme"` (cargo/npm/nuget version detail) predates the scheme and Karma specs assert
   it: it is the one id without a page prefix and must not change.
9. An id is never reused with a different meaning on the same page, and never sits on an element that
   exists in only one of the two list variants with another meaning in the other.
10. Ids are inert: no class, structure or behaviour change. The only structural additions are the
    class-less mobile wrapper `<div data-testid="<page>-cards">` around each mobile `@for` and the
    `<span data-testid="repo-count-value-<type>">` around each dashboard repo count.

### Shared component ids (fixed, scoped by the host id of rule 5)

| Component        | Ids                                                                                                                                                                                                                                                 |
| ---------------- | --------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `danger-modal`   | `danger-modal`, `-backdrop`, `-title`, `-close`, `-question`, `-message`, `-cancel`, `-confirm`                                                                                                                                                     |
| `pagination`     | `pagination`, `pagination-prev`, `pagination-next`, `pagination-page-<n>` (1-based), `pagination-ellipsis`                                                                                                                                          |
| `breadcrumb`     | `breadcrumb`, `breadcrumb-item-<i>` (0-based), `breadcrumb-link`, `breadcrumb-current`                                                                                                                                                              |
| `toast`          | `toast-stack`, `toast` (+ `data-toast-type` = `success`/`error`), `toast-message`, `toast-close`                                                                                                                                                    |
| `searchbox`      | `searchbox`, `search-input`                                                                                                                                                                                                                         |
| `selector`       | `selector`, `selector-toggle`, `selector-menu`, `selector-option-<raw value>`                                                                                                                                                                       |
| `sort-selector`  | `sort-selector`, `sort-selector-toggle`, `sort-selector-menu`, `sort-option-<name>`                                                                                                                                                                 |
| `dropdown`       | `dropdown`, `dropdown-toggle`, `dropdown-menu`                                                                                                                                                                                                      |
| `toggle`         | `toggle`, `toggle-input` (assert `toBeChecked()`; CLICK the `toggle` label, the slider intercepts the input), `toggle-label`                                                                                                                        |
| `radio-group`    | `radio-group`, `radio-option-<value>`                                                                                                                                                                                                               |
| `copy-clipboard` | `copy-button` (+ `data-copied`)                                                                                                                                                                                                                     |
| `tooltip`        | `tooltip-text`, `tooltip-popup`                                                                                                                                                                                                                     |
| others           | `empty-list`, `spinner`, `splash-screen`, `markdown`, `avatar`, `avatar-image`, `avatar-fallback`, `severity-badge` (+ `data-severity`), `severity-breakdown`, `rescan-note`, `status-polling-indicator`, `security-details-link`, `security-badge` |
| shell            | `header`, `header-menu`, `sidebar`, `mobile-sidebar`, `panel-content`, `footer`, `login-page`                                                                                                                                                       |

Modal families use one prefix each (`repo-create-*`, `user-create-*`, `user-edit-*`,
`user-reset-password-*`, `token-create-*`, `token-info-*`, `config-modal-*`, `*-security-modal-*`),
each with `-backdrop`, `-close` and its form fields.

### Protocol pages

The protocol prefix is neutral: one descriptor-driven page object serves all nine formats.

| Prefix          | Meaning                 | Where                                                            |
| --------------- | ----------------------- | ---------------------------------------------------------------- |
| `pkg-list`      | first level at `/:repo` | maven group list, npm/docker/pypi/cargo/helm/nuget/ruby/go lists |
| `pkg-sublist`   | grouping level          | maven `/:repo/:group`, npm `/:repo/:scope`                       |
| `pkg-versions`  | versions of one item    | all version lists, docker tag list                               |
| `pkg-manifests` | docker only             | `/:repo/:image/:tag`                                             |
| `pkg-detail`    | one version             | all version details, docker tag detail                           |

Every list has `pkg-toolbar`, `pkg-search`, `pkg-sort`, `pkg-refresh`, `pkg-configure`,
`pkg-settings`, `<L>-table`/`<L>-row-<key>`, `<L>-cards`/`<L>-card-<key>` and `pkg-error`. Every
detail page has exactly ONE primary install snippet, `pkg-detail-install` (text in
`pkg-detail-install-text`); every other code block is `pkg-detail-snippet-<slug>`.

### Adding a page

Pick a `<page>` prefix, tag every control, row and state (rows on the clickable element, validation
errors per validator, error and empty branches), and update this section in the same PR. The live
inventory is `grep -rn 'data-testid' repsy-frontend/src/app`; the per-page table is deliberately not
duplicated here.
