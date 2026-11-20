-- RPS-2660: the one-time codes of the consent page of a connected application. Same statements as
-- the PostgreSQL V0047.
CREATE TABLE IF NOT EXISTS "connector_authorization_code" (
    "id"             uuid          PRIMARY KEY,
    "user_id"        uuid          NOT NULL,
    "code_hash"      varchar(64)   NOT NULL,
    "client_name"    varchar(150)  NOT NULL,
    "scopes"         varchar(255)  NOT NULL,
    "redirect_uri"   varchar(2048) NOT NULL,
    "code_challenge" varchar(64)   NOT NULL,
    "expires_at"     timestamp     NOT NULL,
    "created_at"     timestamp     NOT NULL,
    CONSTRAINT "fk_connector_authorization_code__user_id"
    FOREIGN KEY ("user_id") REFERENCES "users" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_connector_authorization_code__code_hash"
    ON "connector_authorization_code" ("code_hash");
CREATE INDEX "idx_connector_authorization_code__user_id"
    ON "connector_authorization_code" ("user_id");
CREATE INDEX "idx_connector_authorization_code__expires_at"
    ON "connector_authorization_code" ("expires_at");
