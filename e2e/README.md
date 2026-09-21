# Repsy e2e

An end-to-end protocol-test harness (Playwright + TypeScript), outside the Maven reactor, that
drives real client flows (`mvn deploy`, `npm publish`, ...) against a real Repsy instance, with all
data seeded through the panel API. This is **step 1 ("skeleton")** of the harness: tooling, config,
the panel API client, a seeder with cleanup, a sweep script and the stack/runner containers. It
proves the mechanism with one test suite (`tests/skeleton`); the scenario matrix and the protocol
client adapters are later steps.

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
  playwright.config.ts        # one project so far: "skeleton"
  run.sh                       # single entry point: local | test | sweep
  docker-compose.stack.yml     # postgres:18 + Repsy, started/stopped by `run.sh local up|down`
  docker-compose.runners.yml   # one runner service per protocol; "skeleton" for now
  runners/base.Dockerfile      # node:24 + pinned pnpm + the harness; every runner FROMs this
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
      fixtures.ts                # the base Playwright fixture: seeder with automatic cleanup
  tests/
    skeleton/seed.spec.ts       # proves seeding, cleanup and a real auth probe
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

## Running

```bash
./run.sh local up            # starts postgres:18 + Repsy (built from the repo root Dockerfile;
                              # set REPSY_IMAGE to test a published image instead)
./run.sh test                # runs the "skeleton" runner container against it
./run.sh test --grep '@smoke'
./run.sh test -b             # rebuild the runner image first (Dockerfile/lockfile changed)
./run.sh local down
./run.sh sweep               # deletes e2e-* leftovers older than 24h; --hours N or --all
```

`run.sh test` accepts `--target local|remote|ci` and `--protocol a,b` (a comma-separated list of
runner services; only `skeleton` exists today). Reports land under `e2e/test-results/` (JUnit XML)
and `e2e/playwright-report/` (HTML).

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
./run.sh test
./run.sh test        # again, without resetting the stack — proves run isolation
./run.sh local down
```
