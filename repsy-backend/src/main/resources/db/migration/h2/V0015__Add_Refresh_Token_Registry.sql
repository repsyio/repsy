create table "refresh_tokens" (
    "id" uuid not null primary key,
    "user_id" uuid not null,
    "family_id" uuid not null,
    "expires_at" timestamp with time zone not null,
    "used_at" timestamp with time zone,
    "revoked_at" timestamp with time zone
);

create index "idx_refresh_tokens_family_id" on "refresh_tokens" ("family_id");
create index "idx_refresh_tokens_expires_at" on "refresh_tokens" ("expires_at");
