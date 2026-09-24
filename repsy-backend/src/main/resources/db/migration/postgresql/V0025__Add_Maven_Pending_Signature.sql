-- RPS-1188: a Maven repo that verifies every signature accepts a detached signature (.asc) before the
-- file it signs (or the POM that registers the version) has arrived, which Maven's parallel upload
-- makes routine. The unverified signature is parked here, keyed by repo and by the path of the FILE it
-- signs, and is verified, written to storage and recorded in maven_version_signature when that file
-- arrives. It is kept out of storage on purpose: a signature that did not verify leaves no trace
-- (nothing is served, nothing is charged). Rows are disposable: a scheduled task deletes the ones
-- older than repsy.maven.pending-signature.ttl.
CREATE TABLE "public"."maven_pending_signature" (
    "id"                uuid          PRIMARY KEY,
    "repo_id"           uuid          NOT NULL,
    "signed_file_path"  varchar(2048) NOT NULL,
    "armored_signature" text          NOT NULL,
    "key_id"            varchar(16)   NOT NULL,
    "created_at"        timestamp     NOT NULL,
    CONSTRAINT "fk_maven_pending_signature__repo_id"
        FOREIGN KEY ("repo_id") REFERENCES "public"."repo" ("id") ON DELETE CASCADE,
    CONSTRAINT "ux_maven_pending_signature__repo_id__signed_file_path"
        UNIQUE ("repo_id", "signed_file_path")
);

CREATE INDEX "idx_maven_pending_signature__created_at"
    ON "public"."maven_pending_signature" ("created_at");
