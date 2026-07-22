alter table "public"."repo"
    add column "security_scan_enabled" boolean not null default true;
