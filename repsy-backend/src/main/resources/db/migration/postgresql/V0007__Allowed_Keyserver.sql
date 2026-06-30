CREATE TABLE IF NOT EXISTS "allowed_keyserver"
(
    "id"           uuid         PRIMARY KEY,
    "host"         varchar(255) NOT NULL,
    "display_name" varchar(255) NOT NULL,
    "is_active"    boolean      NOT NULL DEFAULT TRUE,
    "created_at"   timestamp    NOT NULL DEFAULT NOW(),

    CONSTRAINT "uq_allowed_keyserver__host" UNIQUE ("host")
);

-- Seed additional keyservers here as needed (well-known servers stay hardcoded in PGPVerifierService).
INSERT INTO "allowed_keyserver" ("id", "host", "display_name", "is_active", "created_at")
VALUES (gen_random_uuid(), 'keyserver.pgp.com', 'PGP Global Directory',    TRUE, NOW()),
       (gen_random_uuid(), 'pgp.circl.lu',      'CIRCL OpenPGP Keyserver', TRUE, NOW()),
       (gen_random_uuid(), 'pgpkeys.eu',         'PGP Keys EU',             TRUE, NOW());

-- Truncate SSRF-vulnerable free-text URL data before restructuring.
TRUNCATE TABLE "key_store";

ALTER TABLE "key_store"
    ADD COLUMN "allowed_keyserver_id" uuid NOT NULL;

ALTER TABLE "key_store"
    ADD CONSTRAINT "fk_key_store__allowed_keyserver_id"
        FOREIGN KEY ("allowed_keyserver_id") REFERENCES "allowed_keyserver" ("id") ON DELETE CASCADE;

-- Fix OS bug: the old index locked each repo to one custom keyserver.
-- Replace with a compound unique index on (repo_id, allowed_keyserver_id).
DROP INDEX IF EXISTS "ux_key_store__repo_id";

CREATE UNIQUE INDEX "ux_key_store__repo_id__allowed_keyserver_id"
    ON "key_store" ("repo_id", "allowed_keyserver_id");

ALTER TABLE "key_store"
    DROP COLUMN "url";
