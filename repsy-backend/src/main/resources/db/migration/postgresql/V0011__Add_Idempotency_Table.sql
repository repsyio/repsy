create table "public"."gitnode_idempotency" (
    "id" uuid primary key,
    "key" uuid unique not null,
    "created_at" timestamp not null
);
