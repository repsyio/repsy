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
per-FILENAME override rule (see "PyPI runner" below). Every other protocol (golang, ruby) replicates
the same model in later steps; nothing about the model itself is maven-specific.

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
  playwright.config.ts        # one project per protocol: "skeleton", "maven", "npm", "cargo", "nuget", "docker", "helm", "pypi"
  run.sh                       # single entry point: local | test | sweep
  docker-compose.stack.yml     # postgres:18 + Repsy, started/stopped by `run.sh local up|down`
  docker-compose.runners.yml   # one runner service per protocol: "skeleton", "maven", "npm", "cargo", "nuget", "docker", "helm", "pypi"
  runners/base.Dockerfile      # node:24 + pinned pnpm + the harness; the "skeleton" runner
  runners/maven.Dockerfile     # + pinned Temurin/Maven; see "Adding a protocol adapter" below
  runners/npm.Dockerfile       # + nothing else: npm ships with the node:24 base already
  runners/cargo.Dockerfile     # + a pinned Rust toolchain, copied in from the official rust image
  runners/nuget.Dockerfile     # + a pinned .NET SDK, copied in from the official Ubuntu-noble SDK image
  runners/docker.Dockerfile    # + the static `crane` binary copied out of its own distroless image; no daemon, no socket
  runners/helm.Dockerfile      # + the static `helm` binary + the cm-push plugin installed at build time; no daemon, no socket
  runners/pypi.Dockerfile      # + a pinned CPython copied out of the official python image; pip/twine installed at build time
  runners/entrypoint.sh         # regenerates the API client, then runs Playwright for one project
  src/
    env.ts                     # typed config from env/.env
    target.ts                  # capabilities derived from REPSY_TARGET
    api/
      panel-api.ts             # hand-written wrapper around the generated client
      generated/                # `pnpm gen:api` output, git-ignored
    seed/
      run-id.ts                 # e2e-<runid>- naming, length/pattern limits
      seeder.ts                 # createUser/createRepo/setSettings/createToken + cleanup()
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
    packages/
      maven/                     # mustache templates of the tiny jar project + settings.xml
      npm/                       # mustache templates of the tiny package.json/index.js + .npmrc
      cargo/                     # mustache templates of the tiny crate + consumer Cargo.toml + .cargo/config.toml
      nuget/                     # mustache templates of nuget.config + the consumer .csproj (the .nupkg itself is built in code, see nuget-raw.ts)
      docker/                    # config.template.json (DOCKER_CONFIG auths entry; the image itself is built in code, see docker-image.ts)
      helm/                      # Chart.template.yaml + registry-config.template.json (HELM_REGISTRY_CONFIG auths entry)
      # no packages/pypi/: the wheel is built entirely in code (line-based text), see pypi-raw.ts's buildWheel
  tests/
    skeleton/seed.spec.ts       # proves seeding, cleanup and a real auth probe
    maven/
      publish-consume.spec.ts   # registerPublishConsumeLoop(mavenAdapter) + the RPS-1196 real-client test
      upload-rules.spec.ts      # raw-HTTP pins of the override / releases / snapshots upload rules
      pgp-signature.spec.ts     # registered PGP public keys (RPS-1189): verify, reject, isolate, delete
      remote-throttle.spec.ts   # sanity check of RemoteAuthBudget/withBackoff429, no server needed
    npm/
      publish-consume.spec.ts   # registerPublishConsumeLoop(npmAdapter) + a scoped-package real-client test
      registry-rules.spec.ts    # raw-HTTP pins of override/version-validation rules + the RPS-1205 tarball probe
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
| `REPSY_E2E_INSECURE_REGISTRY` | _(unset)_                  | docker runner's `--insecure` (only needed for a remote plain-HTTP host; `localhost` already works without it); helm runner's `--insecure-skip-tls-verify` (a REMOTE HTTPS target with a bad cert only -- helm's own `--plain-http` is derived from `REPSY_REPO_BASE_URL`'s scheme instead, unconditionally on this harness's own `http://localhost:9090` stack, confirmed live H3: unlike `crane`, Helm has no localhost auto-detection) |

## Targets (`src/target.ts`)

- **local** — a stack this harness starts and owns (`./run.sh local up`); throttle limits can be
  raised freely for negative-auth scenarios.
- **ci** — a pipeline-started stack; same freedoms as `local`. Wiring is deferred (see the plan).
- **remote** — an already-running instance the harness does not own or reset. Throttle cannot be
  tuned and nothing global is touched; later steps add a failure budget and a preflight check.

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

### RPS-1205, confirmed live: the exact shape

`PackageUtils.fixTarballUrl` (`repsy-protocols/npm/.../shared/utils/PackageUtils.java`) rewrites a
version's `dist.tarball` at publish time by splicing the repo name into the URL's path at a fixed
offset, a transform whose own javadoc describes a cloud, multi-tenant path shape
(`/npm/username/@foo/demo/-/@foo/demo-0.2.1.tgz`) Repsy OS does not have. On OS, a real npm client's
own `dist.tarball` (computed client-side as `<registry>/<name>/-/<tarballFilename>`, i.e. already
just `/<repoName>/<packagePath>/-/<file>`) gets a **second, wrong `/<repoName>/` segment spliced into
the middle of the path**. Live evidence from one run (`tests/npm/registry-rules.spec.ts`):

```
canonical path "e2e-y50b4j9002-tarball/-/e2e-y50b4j9002-tarball-0.22833922.2.tgz" -> 200
dist.tarball   "http://localhost:9090/e2e-y50b4j9002-npm-1/e2e-y50b4j9002-tarball/e2e-y50b4j9002-npm-1/-/e2e-y50b4j9002-tarball-0.22833922.2.tgz" -> 404
```

(`e2e-y50b4j9002-npm-1` — the repo name — appears twice: once correctly, as the request's own repo
segment, and once spliced in mid-path by `fixTarballUrl`.) The **canonical path always serves the
real, byte-correct tarball** (confirmed by content hash, not just status); **`dist.tarball` always
answers `404`**, confirmed on unscoped and scoped packages alike (`tests/npm/publish-consume.spec.ts`'s
scoped-package test). This is what makes RPS-1205 a URL-construction bug, not a storage one, and why
`npmAdapter.knownConsumeFailure` routes only the final client-exit-code/content-equality consume
assertions through `test.fail()` (`scenarios/loop.ts`) — the auth-only packument-GET outcome is
asserted for real, same as every other scenario, and never weakened.

### A second, distinct backend bug found live (not RPS-1205, not fixed here)

Re-publishing (redeploying, `allowOverride: true`) an **existing** npm version whose manifest has no
`keywords` field crashes with `400 badRequest`, swallowing a `ClassCastException`:
`PackageUtils.liftFieldsToTopLevel` defaults an absent version `keywords` onto the **top-level**
packument as a native `new String[] {}`; `NpmPackageServiceImpl.updateVersionFromMetadata` (reached
only on a re-publish of an _existing_ version, via `AbstractNpmProtocolFacade.publish`'s "already
exists" branch) then calls `addKeywords`/`addMaintainers` with that **top-level** payload instead of
the version's own sub-object, and `addKeywords` casts what it finds at `"keywords"` to
`ArrayList<String>` — which throws, because the value is a `String[]`, not an `ArrayList`, when it
came from that default. A first-ever publish of a package never hits this (`addPackage`'s DB path
passes the version's own sub-object, which legitimately has no `"keywords"` key, so the read is
`null` and skipped safely); only a **redeploy of an already-existing version** does. Confirmed live by
adding `"keywords": []` to a version manifest, which alone made an otherwise-identical redeploy
succeed. This is **not** the same bug as RPS-1205 (a different bug, a different code path, no
relation to tarball URLs) — it is filed as **RPS-1211** — and **not fixed here** (out of scope, per
the plan) — `clients/npm-raw.ts`'s `buildPublishDocument` and `src/packages/npm/package.template.json`
both always include `"keywords": []` (a real npm client's own normalised manifest almost always does
too), which routes around it without touching backend code.

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
  live, an underscore-named crate's poll resolves on its first attempt (well under a second), so
  nothing here relies on the longer default (see "H2" below for the one case that does hit it).
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

### H1, confirmed live: a refused duplicate publish still corrupts storage (RPS-1124)

`AbstractCargoProtocolFacade.publish` writes the `.crate` bytes to storage (`FileSystemStorageStrategy
.write`, `TRUNCATE_EXISTING` — overwrites whatever was already there) and appends an index line to a
storage-only index FILE (never served — the sparse-index HTTP handler reads the DB instead) **BEFORE**
calling `CargoCrateServiceImpl.publish`, where the duplicate-version check
(`checkExistsVersion`) actually lives. When that check throws, the (Postgres) transaction the DB
writes were inside rolls back — but the storage write already happened and is not, and cannot be,
rolled back with it. Live evidence (`tests/cargo/registry-rules.spec.ts`):

```
seed publish (bytesA)                              -> 200
download after seed                                 -> 200, equals bytesA
duplicate publish (bytesB, allowOverride: false)    -> 400 "crate `...` already exists in this registry"
served (DB-backed) sparse index after the duplicate -> unchanged (still bytesA's cksum)
download after the duplicate                        -> 200, equals bytesB, NOT bytesA
```

The served index still names bytesA's checksum, but a download now serves bytesB's bytes — a real
consumer's own sha256 check (`cargo fetch` verifies the downloaded `.crate` against the index
`cksum`) would then fail for a version that was never touched by any _accepted_ publish. This is why
`clients/cargo.ts` declares `ProtocolAdapter.knownPublishSideEffect` (a new hook this step added to
`scenarios/adapter.ts`/`scenarios/loop.ts`, the publish-side analogue of npm's
`knownConsumeFailure`): it routes only the loop's `expectNothingStored` comparison for `no-override`/
`override` (the only catalog scenarios that redeploy an existing version with a credential that
passes auth) through `test.fail()`, never the outcome or client-exit-code assertions, which are
asserted for real like every other scenario. Tracked by **RPS-1124** ("Audit the Cargo, Helm, PyPI,
npm and Go publish paths for storage-before-DB ordering"), an already-open story this live evidence
was added to as a comment rather than a new ticket, since it already scoped exactly this class of fix
for Cargo by name.

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
name round-trips through the loop under a spelling the server itself never agrees to serve back.
`tests/cargo/publish-consume.spec.ts`'s dedicated real-client test and
`tests/cargo/registry-rules.spec.ts`'s raw-HTTP test both pin this directly, through `test.fail()`.
Filed as **RPS-1212**.

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
and the download path; the service index's exact `@id`/`@type` shape including the three
non-standard types (H6); and `X-NuGet-ApiKey`'s three cases (H7).

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
- **H6** (`dotnet package search`/registration-based commands fail; push/restore do not): the service
  index's exact `@type` shape was confirmed live
  (`tests/nuget/registry-rules.spec.ts`) — `RegistrationsBaseUrl/3.0.0`, `SearchQueryService/3.0.0`,
  `SearchAutocompleteService/3.0.0` and a non-standard `PackageDelete/2.0.0`, none of which
  NuGet.Client's `ServiceTypes.cs` resolves, while `PackageBaseAddress/3.0.0`/`PackagePublish/2.0.0`
  (what push/restore use) are correct. This step did not additionally run
  `dotnet package search`/`dotnet list package` against the server to watch it fail live (the
  service-index evidence alone is what the plan's own hypothesis was about) — filed as **RPS-1213**.
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
`HEAD` vs. `GET` by digest (R8, **B1**); retagging the same digest under a second tag (R9); a
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
- **H10** (`HEAD` vs. `GET` by digest): confirmed — `HEAD` by digest is `404` even right after a
  `GET` by that same digest served `200` — **B1 (RPS-1215)** (R8). Notably, `crane digest <ref>@sha256:<digest>`
  (which is a `HEAD` under the hood) does **NOT** itself fail: ggcr's own `remote.Head` falls back to
  a `GET` when the `HEAD` fails (confirmed live, `crane`'s own stderr: `"HEAD request failed, falling
back on GET"`), so B1 is invisible to `crane digest`'s own exit code — only a raw `HEAD` (or a
  client without that specific fallback) observes it. The "D3" real-client test pins BOTH facts.
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

- **B1 (filed as [RPS-1215](https://zyfera.atlassian.net/browse/RPS-1215))** — `HEAD` a manifest by
  digest answers `404` for a manifest a `GET` of that SAME digest serves fine (distribution spec:
  "HEAD MUST be identical to GET without the body"). `AbstractDockerManifestCheckProtocolMethodHandler`'s
  `findTagAndManifest` only ever resolves a TAG row, never a digest — `AbstractDockerProtocolTxFacade`'s
  own `resolveManifestDigest` (which GET uses) does both. Confirmed live: `tests/docker/registry-rules
.spec.ts`'s R8 (raw) and `tests/docker/publish-consume.spec.ts`'s D3 (`crane digest <ref>@sha256:<digest>`
  — masked by ggcr's own HEAD→GET fallback, see "H10" above, so a raw `HEAD` is what actually exposes it).
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
  answer was right, confirmed live** — it does NOT fail. `helm registry login localhost:9090
--plain-http -u <user> --password-stdin` with an intentionally wrong secret still prints "Login
  Succeeded", exit 0, and writes the (wrong) credential into `HELM_REGISTRY_CONFIG` regardless —
  see **B-H4** below.
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
- **B-H4 (filed as [RPS-1220](https://zyfera.atlassian.net/browse/RPS-1220))** — Lives in the
  DOCKER provider's token endpoint, surfaces through Helm's shared `/v2/` ping: `helm registry
login` succeeds with a WRONG password. Docker's `/v2/token` answers the ping's own OAuth2-form
  POST (no `Authorization` header at all — oras-go's `ForceAttemptOAuth2` path, requesting the
  wildcard `repository:*:pull` scope) with an ANONYMOUS token, `200`, before any credential is ever
  checked; `helm registry login` treats that `200` as success. Confirmed live: "HL1". The push/pull
  REQUEST itself is still credential-checked for real (a wrong-password login still fronts a
  failing push) — this only affects the login COMMAND's own reported success, never an actual
  write/read.
- **B-H5** (observation) — A manifest is only addressable by the exact reference it was pushed
  under: `GET`/`HEAD` by digest of a tag-pushed manifest both `404`. Would affect `helm pull
oci://...@sha256:<digest>` if that were expected to work; not otherwise exercised by any real
  client flow this step drives.
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
trailing-slashed project page (H14); `HEAD` answering 200 unconditionally (RPS-1226, an observation);
a pre-release/dev/post version publishing fine under `releases:false`/`snapshots:false` (H23); and an
unknown package/file 404ing. It also pins five backend bugs found while reading the server source and
confirmed live (see below), each via `test.fail()`.

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
  and with an EXTRA quirk beyond the plan's own prediction — the response's `Content-Type` is
  `application/json`, not merely "unset"/defaulted, despite the body being HTML (RPS-1221 below).
- **H14** (a non-normalized/no-trailing-slash name 307-redirects to the normalized page): confirmed
  live, both via a raw probe and inside a real-client dedicated test (`publish-consume.spec.ts`'s
  H21 test).
- **H15** (a version/filename mismatch bypasses `allowOverride: false`): confirmed live — see
  RPS-1223.
- **H16** (`badVersionString` leaves an orphaned, downloadable file+sidecar): confirmed live — see
  P4/RPS-1124.
- **H17** (a missing `sha256_digest` is a 500; a wrong one is served as-is): confirmed live — see
  RPS-1224/RPS-1225.
- **H18** (`HEAD` of a never-published path is 200): confirmed live — see RPS-1226 (observation).
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
  `1.0.0`/`1.0.0a1`/`1.0.0.post1`/`1.0.0.dev0` all publish and serve fine under
  `releases:false, snapshots:false`.
- **H24** (the runner image's stdlib import check passes with the derived apt list): confirmed live
  on the FIRST build attempt — see this section's opening paragraph for the exact package list and
  how it was derived (`ldd` against a live container, not a generic runtime-deps list).

### Backend bug candidates found while reading and confirmed live (do not fix here)

- **RPS-1221** — `packages.ftl` (the root `/simple/` index, rendered by
  `PypiSimpleHandlerPreProcessor`) hard-codes cloud-layout hrefs, `/pypi/<repoName>/simple/<name>/`,
  that 404 on Repsy OS's single-tenant layout (the real, working path is `/<repoName>/simple/<name>/`
  — no `/pypi/` prefix at all here). A second, narrower quirk found only by probing live (not
  predicted by the plan): the response's own `Content-Type` is `application/json`, not `text/html`,
  despite the body being this same malformed HTML. Low impact — `pip install`/`download` never fetch
  the root page, only `/simple/<project>/` — but a real PEP 503 spec violation. Confirmed live:
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
- **P4** (storage-before-DB — the RPS-1124 family already open for cargo/nuget; comment there, not a
  new ticket) — `AbstractPypiStorageService.writePackageArchive` (the archive file AND its `.sha256`
  sidecar) runs BEFORE `PypiPackageServiceImpl.addOrUpdateRelease`, where `ReleaseVersion.of(form
.version)` can still throw `badVersionString`. A validation failure after the storage write leaves an
  orphaned, directly-downloadable archive+sidecar with no DB row and no project-page entry at all.
  Confirmed live: `registry-rules.spec.ts`'s badVersionString test.
- **RPS-1224 / RPS-1225** — The `.sha256` sidecar is the client-sent `sha256_digest` form field stored
  VERBATIM, never recomputed or verified against the actual uploaded bytes
  (`uploadForm.getSha256_digest().getBytes()`). A MISSING digest crashes with an unhandled NPE
  (`500`, confirmed live — RPS-1224); a WRONG digest is silently served to every consumer as if
  correct (confirmed live — RPS-1225). Confirmed: `registry-rules.spec.ts`'s two digest tests.
- **RPS-1226** (observation, not routed around — nothing in the catalog loop's own scenarios
  depends on `HEAD` meaning "exists") — `HEAD` on ANY path under a pypi repo answers `200`,
  unconditionally; existence is never checked. Confirmed live: `registry-rules.spec.ts`'s HEAD test.
- **P7** (observation, no test) — `PypiPackageServiceImpl.updateRelease` (read while investigating
  `addOrUpdateRelease`) throws a bare `IllegalStateException` in what reads as an unreachable branch;
  noted only, not independently confirmed live (no code path in this harness's own scenarios reaches
  it).

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
./run.sh test --protocol skeleton,maven,npm,cargo,nuget,docker,helm
./run.sh test --grep '@smoke'
./run.sh test -b             # rebuild the runner image(s) first (Dockerfile/lockfile changed)
./run.sh local down
./run.sh sweep               # deletes e2e-* leftovers older than 24h; --hours N or --all
```

`run.sh test` accepts `--target local|remote|ci` and `--protocol a,b` (a comma-separated list of
runner services: `skeleton`, `maven`, `npm`, `cargo`, `nuget`, `docker`, `helm`). Reports land under `e2e/test-results/` (JUnit
XML) and
`e2e/playwright-report/` (HTML) — one `run.sh test` invocation covering several `--protocol` services
overwrites that JUnit file per service, so diff/compare a single protocol's run in isolation
(`--protocol maven` alone) rather than a combined one if you need its own report.

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
the flipped `'ok'` expectation, then reverted). `./run.sh sweep --dry-run` lists any `e2e-*`
leftovers without deleting them.

The npm suite's `'ok'`-expected scenarios currently report as an _expected_ failure
(`test.fail`, RPS-1205 — see "npm runner" above), not a plain pass: Playwright's list reporter still
prints a `✘` for each (something inside the test body did throw, which is exactly what `test.fail`
is watching for), but the run's own summary line and exit code both say "passed"/`0` — treat those
two as authoritative over the per-line glyphs. Likewise for the cargo suite's `no-override`/
`override` scenarios (`knownPublishSideEffect`, "H1" above) and its two dedicated hyphen tests ("H2"
above), the docker suite's four `test.fail`-routed registry-rules tests (R5/B4, R7/B2, R8/B1,
R12/B5 — "H9"/"H10"/"H13" above), and the helm suite's five `test.fail`-routed tests (HL1/B-H4,
HL2/B-H3, HL4/B-H1, HL5/B-H2, R8/B-H3 — "Helm runner" above): all counted as "passed", not a plain
pass line.
