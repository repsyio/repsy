-- RPS-2757: the high-impact consent of a CLI login. A TEMPORARY token whose user ticked "high-impact
-- operations for AI agents" on the authorize screen carries it, and rp mcp then offers its tools
-- that delete or rename a repository, give others access or create tokens. False for every other
-- row. A constant default is stored in the catalog without rewriting the table.
SET lock_timeout = '5s';

ALTER TABLE "personal_access_token" ADD COLUMN IF NOT EXISTS "high_impact" boolean NOT NULL DEFAULT false;
