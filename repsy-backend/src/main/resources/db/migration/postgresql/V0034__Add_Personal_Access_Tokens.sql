-- RPS-1901: personal access tokens, the credential of a user (not of a repository, unlike a deploy
-- token) for CI and the Repsy CLI. Only the SHA-256 hash of the secret is stored; the secret itself
-- is shown once, when the token is created.
--
-- "scopes" is one comma separated list in canonical order (see TokenScopeConverter). "expiration_date"
-- is never null: a token without a date is given the default expiry when it is created. The foreign
-- key cascades, so deleting a user revokes every token the user holds.
CREATE TABLE IF NOT EXISTS "personal_access_token" (
    "id"              uuid         PRIMARY KEY,
    "user_id"         uuid         NOT NULL,
    "name"            varchar(150) NOT NULL,
    "token_hash"      varchar(64)  NOT NULL,
    "scopes"          varchar(255) NOT NULL,
    "expiration_date" timestamp    NOT NULL,
    "last_used_at"    timestamp,
    "created_at"      timestamp    NOT NULL,
    CONSTRAINT "fk_personal_access_token__user_id"
    FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE
    );

-- A hash is looked up on every request that presents a token, and two tokens must never share one.
CREATE UNIQUE INDEX "ux_personal_access_token__token_hash" ON "personal_access_token" ("token_hash");
CREATE INDEX        "idx_personal_access_token__user_id"   ON "personal_access_token" ("user_id");
