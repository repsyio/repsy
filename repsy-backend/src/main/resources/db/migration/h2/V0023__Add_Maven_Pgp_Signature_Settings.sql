-- RPS-1188: a Maven repo can verify every artifact signature (.jar.asc, -sources.jar.asc, ...), not
-- only the .pom.asc. RPS-1204: a repo can switch the public key-server fallback off (air-gapped
-- installs). Both settings are Maven only; the columns exist on every repo row.
alter table "repo"
    add column "pgp_verify_all_signatures_enabled" boolean not null default false;
alter table "repo"
    add column "pgp_key_server_lookup_enabled" boolean not null default true;

-- RPS-1188: one row per stored file of a version whose detached signature was verified. file_name is
-- the signed file (lib-1.0-sources.jar), not its .asc. A version is "signed" under
-- pgp_verify_all_signatures_enabled when every signable file of it has a row here.
CREATE TABLE "maven_version_signature" (
    "id"                  uuid          PRIMARY KEY,
    "artifact_version_id" uuid          NOT NULL,
    "file_name"           varchar(1024) NOT NULL,
    "verified_at"         timestamp     NOT NULL,
    CONSTRAINT "fk_maven_version_signature__artifact_version_id"
        FOREIGN KEY ("artifact_version_id") REFERENCES "maven_artifact_version" ("id") ON DELETE CASCADE,
    CONSTRAINT "ux_maven_version_signature__artifact_version_id__file_name"
        UNIQUE ("artifact_version_id", "file_name")
);

-- A release version whose POM signature was verified before this table existed keeps a .pom row, so
-- it is not judged unsigned when the repo turns every-signature verification on. A snapshot's POM
-- has a timestamped name that is not known here, so snapshots are not backfilled.
INSERT INTO "maven_version_signature" ("id", "artifact_version_id", "file_name", "verified_at")
SELECT random_uuid(),
       v."id",
       a."artifact_name" || '-' || v."version_name" || '.pom',
       COALESCE(v."last_updated_at", NOW())
FROM "maven_artifact_version" v
         JOIN "maven_artifact" a ON a."id" = v."artifact_id"
WHERE v."signed" = true
  AND v."version_name" NOT LIKE '%SNAPSHOT';
