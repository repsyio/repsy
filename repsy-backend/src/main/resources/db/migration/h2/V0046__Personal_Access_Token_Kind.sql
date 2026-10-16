-- RPS-2039: a personal access token has a kind (PERSONAL, the long-lived token a user creates, or
-- TEMPORARY, the 24-hour token a browser authorization hands to the Repsy CLI). Same statement as
-- the PostgreSQL V0046 without lock_timeout, which H2 does not have. Existing rows are PERSONAL.
ALTER TABLE "personal_access_token" ADD COLUMN "kind" varchar(20) NOT NULL DEFAULT 'PERSONAL';
