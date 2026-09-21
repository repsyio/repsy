# Repsy e2e

An end-to-end protocol-test harness (Playwright + TypeScript), outside the Maven reactor, that
drives real client flows (`mvn deploy`, `npm publish`, ...) against a real Repsy instance, with all
data seeded through the panel API. Step 1 ("skeleton") built the tooling, config, the panel API
client, a seeder with cleanup, a sweep script and the stack/runner containers, proven by one test
suite (`tests/skeleton`). This is **step 2 ("scenario engine + maven")**: the scenario model
(`src/scenarios/`) and its catalog, the maven client adapter and runner, and the full catalog
running green against a local stack. Since then the maven catalog also covers SNAPSHOT deploys and
redeploys, and the refusal scenarios check that nothing was stored ("SNAPSHOT and redeploy
behaviour, as probed", below). Every other protocol (npm, cargo, nuget, docker, helm, pypi,
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
  playwright.config.ts        # one project per protocol: "skeleton", "maven"
  run.sh                       # single entry point: local | test | sweep
  docker-compose.stack.yml     # postgres:18 + Repsy, started/stopped by `run.sh local up|down`
  docker-compose.runners.yml   # one runner service per protocol: "skeleton", "maven"
  runners/base.Dockerfile      # node:24 + pinned pnpm + the harness; the "skeleton" runner
  runners/maven.Dockerfile     # + pinned Temurin/Maven; see "Adding a protocol adapter" below
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
      types.ts                  # Scenario/Outcome model, outcomeForStatus()
      catalog.ts                # the scenario matrix -- see "Scenario model" below
      world.ts                  # World/Coordinates types, the seed-publisher registry
      fixtures.ts                # Playwright fixtures: panelApi, seeder, world(scenario, protocol)
      remote-throttle.ts        # RemoteAuthBudget/withBackoff429 -- see "Remote hardening" below
    clients/
      exec.ts                   # execa wrapper: isolated work dir/HOME, redacted logs, attach-on-fail
      maven.ts                  # the maven adapter: publish()/resolve(), raw-HTTP status pinning
      maven-raw.ts              # raw PUT/GET, repo-tree fingerprint, maven-metadata.xml builders/parsers
    packages/
      maven/                    # mustache templates of the tiny jar project + settings.xml
  tests/
    skeleton/seed.spec.ts       # proves seeding, cleanup and a real auth probe
    maven/
      publish-consume.spec.ts   # the scenario loop for maven
      upload-rules.spec.ts      # raw-HTTP pins of the override / releases / snapshots upload rules
      remote-throttle.spec.ts   # sanity check of RemoteAuthBudget/withBackoff429, no server needed
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
  both kinds and is never judged; checksum files (`.sha1`/`.md5`) carry no kind and are never judged.
- **`allowOverride: false`** refuses re-uploading a file that already exists
  (`403 artifactOverrideIsProhibited`) and never judges metadata. A normal SNAPSHOT redeploy writes
  new timestamped files and re-uploads the metadata, so it **succeeds** under `allowOverride: false`
  (`snapshot-redeploy-no-override`: real client exit 0, consumer resolves buildNumber 2). Only a name
  that already exists is an override, and a real client never sends one twice.
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

1. `src/clients/<protocol>.ts`: `publish(world)`/`resolve(world)` (or that protocol's equivalent
   verbs) returning an `AdapterResult` (`{ outcome, httpStatus, clientExitCode, command }`), derived
   from a **raw HTTP request with the same credential**, not from the client's exit code (see
   `clients/maven.ts`'s file header: a real client hides the HTTP status behind its own exit code).
   Register a seed publisher at module load: `registerSeedPublisher('<protocol>', async (world) =>
{...})` (see `scenarios/world.ts`).
2. `src/packages/<protocol>/`: mustache templates of a tiny publishable project.
3. `runners/<protocol>.Dockerfile`: the toolchain that protocol's client needs, pinned versions as
   build args. See `runners/maven.Dockerfile`'s header comment for why it repeats
   `runners/base.Dockerfile`'s early layers instead of `FROM`ing it as a separately built image.
4. A service in `docker-compose.runners.yml` (copy the `maven` service: same `x-runner-common`/
   `x-runner-environment` anchors, its own named volume for third-party downloads if the client
   caches those).
5. A project in `playwright.config.ts` (`testMatch: '<protocol>/**/*.spec.ts'`).
6. `tests/<protocol>/publish-consume.spec.ts`: the scenario loop (copy
   `tests/maven/publish-consume.spec.ts`).
7. Restrict any scenario your adapter cannot express (or add one only it needs) via the catalog
   entry's `protocols` field.

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
./run.sh test --protocol skeleton,maven
./run.sh test --grep '@smoke'
./run.sh test -b             # rebuild the runner image(s) first (Dockerfile/lockfile changed)
./run.sh local down
./run.sh sweep               # deletes e2e-* leftovers older than 24h; --hours N or --all
```

`run.sh test` accepts `--target local|remote|ci` and `--protocol a,b` (a comma-separated list of
runner services: `skeleton`, `maven`). Reports land under `e2e/test-results/` (JUnit XML) and
`e2e/playwright-report/` (HTML).

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
./run.sh test                    # the skeleton project
./run.sh local down
```

After a run, confirm no `e2e-*` repos or users remain: `GET /api/repos/{repoType}/info` for every
`RepoType` and `GET /api/users` should list none. A meaningfulness check for the maven catalog:
temporarily flip one scenario's expectation in `catalog.ts` (e.g. `token-expired`'s publish to
`'ok'`, or `redeploy-snapshots-off`'s), confirm `./run.sh test --protocol maven --grep <id>` fails,
then restore it. `./run.sh sweep --dry-run` lists any `e2e-*` leftovers without deleting them.
