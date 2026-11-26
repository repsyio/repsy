-- RPS-2757: the high-impact consent of a CLI login. Same statement as the PostgreSQL V0048 without
-- lock_timeout, which H2 does not have.
ALTER TABLE "personal_access_token" ADD COLUMN IF NOT EXISTS "high_impact" boolean NOT NULL DEFAULT false;
