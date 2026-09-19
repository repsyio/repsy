alter table "public"."users"
    add column "token_version" integer not null default 0;
