-- RPS-2039: a personal access token has a kind. PERSONAL is the long-lived token a user creates
-- (the only kind before this); TEMPORARY is the 24-hour token a browser authorization hands to the
-- Repsy CLI. Both live in this table so that one lookup, one hash and one expiry check serve every
-- credential entry point. Every existing row is a PERSONAL token, which the default says. The
-- column is added with a constant default, which PostgreSQL 11 and later stores in the catalog
-- without rewriting the table.
SET lock_timeout = '5s';

ALTER TABLE "personal_access_token" ADD COLUMN "kind" varchar(20) NOT NULL DEFAULT 'PERSONAL';
