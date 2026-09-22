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
duplicate publish — see "Cargo runner" below). Every other protocol (nuget, docker, helm, pypi,
golang, ruby) replicates the same model in later steps; nothing about the model itself is
maven-specific.

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
  playwright.config.ts        # one project per protocol: "skeleton", "maven", "npm", "cargo"
  run.sh                       # single entry point: local | test | sweep
  docker-compose.stack.yml     # postgres:18 + Repsy, started/stopped by `run.sh local up|down`
  docker-compose.runners.yml   # one runner service per protocol: "skeleton", "maven", "npm", "cargo"
  runners/base.Dockerfile      # node:24 + pinned pnpm + the harness; the "skeleton" runner
  runners/maven.Dockerfile     # + pinned Temurin/Maven; see "Adding a protocol adapter" below
  runners/npm.Dockerfile       # + nothing else: npm ships with the node:24 base already
  runners/cargo.Dockerfile     # + a pinned Rust toolchain, copied in from the official rust image
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
    packages/
      maven/                     # mustache templates of the tiny jar project + settings.xml
      npm/                       # mustache templates of the tiny package.json/index.js + .npmrc
      cargo/                     # mustache templates of the tiny crate + consumer Cargo.toml + .cargo/config.toml
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
```

## Setup

```bash
cd e2e
cp .env.example .env   # fill in REPSY_ADMIN_PASSWORD
pnpm install
pnpm gen:api            # generates src/api/generated from ../repsy-backend's openapi spec
```

## Environment

| Variable                      | Default                    | Notes                                           |
| ----------------------------- | -------------------------- | ----------------------------------------------- |
| `REPSY_API_BASE_URL`          | `http://localhost:8080`    | panel API                                       |
| `REPSY_REPO_BASE_URL`         | `http://localhost:9090`    | repository/protocol operations                  |
| `REPSY_ADMIN_USERNAME`        | `admin`                    |                                                 |
| `REPSY_ADMIN_PASSWORD`        | _(none — required)_        | must match the target's admin password          |
| `REPSY_TARGET`                | `local`                    | `local` \| `remote` \| `ci` — see Targets below |
| `REPSY_E2E_RUN_ID`            | random 6-char lowercase id | shared by every runner in one `run.sh test`     |
| `REPSY_E2E_INSECURE_REGISTRY` | _(unset)_                  | reserved for the future docker/helm runners     |

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
machinery. `clients/maven-adapter.ts`, `clients/npm.ts`'s `npmAdapter` and `clients/cargo.ts`'s
`cargoAdapter` are the three worked examples.

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
./run.sh test --protocol skeleton,maven,npm,cargo
./run.sh test --grep '@smoke'
./run.sh test -b             # rebuild the runner image(s) first (Dockerfile/lockfile changed)
./run.sh local down
./run.sh sweep               # deletes e2e-* leftovers older than 24h; --hours N or --all
```

`run.sh test` accepts `--target local|remote|ci` and `--protocol a,b` (a comma-separated list of
runner services: `skeleton`, `maven`, `npm`, `cargo`). Reports land under `e2e/test-results/` (JUnit
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
./run.sh test                    # the skeleton project
./run.sh sweep --dry-run         # before tearing down: confirms nothing was left behind
./run.sh local down
```

After a run, confirm no `e2e-*` repos or users remain: `GET /api/repos/{repoType}/info` for every
`RepoType` and `GET /api/users` should list none (`./run.sh sweep --all --dry-run` does this for
you). A meaningfulness check for the maven catalog: temporarily flip one scenario's expectation in
`catalog.ts` (e.g. `token-expired`'s publish to `'ok'`, or `redeploy-snapshots-off`'s), confirm
`./run.sh test --protocol maven --grep <id>` fails, then restore it. `./run.sh sweep --dry-run` lists
any `e2e-*` leftovers without deleting them.

The npm suite's `'ok'`-expected scenarios currently report as an _expected_ failure
(`test.fail`, RPS-1205 — see "npm runner" above), not a plain pass: Playwright's list reporter still
prints a `✘` for each (something inside the test body did throw, which is exactly what `test.fail`
is watching for), but the run's own summary line and exit code both say "passed"/`0` — treat those
two as authoritative over the per-line glyphs. Likewise for the cargo suite's `no-override`/
`override` scenarios (`knownPublishSideEffect`, "H1" above) and its two dedicated hyphen tests ("H2"
above): all `test.fail`-routed, all counted as "passed".
