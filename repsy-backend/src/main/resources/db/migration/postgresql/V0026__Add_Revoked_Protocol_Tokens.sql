create table "public"."revoked_protocol_tokens" (
    "token_hash" varchar(64) not null primary key,
    "expires_at" timestamp with time zone not null,
    "revoked_at" timestamp with time zone not null
);

create index "idx_revoked_protocol_tokens_expires_at" on "public"."revoked_protocol_tokens" ("expires_at");
