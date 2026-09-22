-- RPS-1189: a repo's Maven key store can hold armored OpenPGP public keys, consulted before any
-- key server when a .pom.asc is verified. One row per registered key block (primary key plus
-- its subkeys); key_id and fingerprint are those of the primary key, in upper-case hex.
CREATE TABLE "pgp_public_key" (
    "id"          uuid         PRIMARY KEY,
    "repo_id"     uuid         NOT NULL,
    "key_id"      varchar(16)  NOT NULL,
    "fingerprint" varchar(64)  NOT NULL,
    "user_id"     varchar(255),
    "armored_key" text         NOT NULL,
    "created_at"  timestamp    NOT NULL DEFAULT NOW(),
    CONSTRAINT "fk_pgp_public_key__repo_id"
        FOREIGN KEY ("repo_id") REFERENCES "repo" ("id") ON DELETE CASCADE
);

CREATE UNIQUE INDEX "ux_pgp_public_key__repo_id__fingerprint"
    ON "pgp_public_key" ("repo_id", "fingerprint");
CREATE INDEX "idx_pgp_public_key__repo_id" ON "pgp_public_key" ("repo_id");
