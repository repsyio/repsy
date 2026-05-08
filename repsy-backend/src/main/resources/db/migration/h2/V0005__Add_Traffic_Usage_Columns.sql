ALTER TABLE "repo" ADD COLUMN IF NOT EXISTS "inbound_traffic_usage" bigint NOT NULL DEFAULT 0;
ALTER TABLE "repo" ADD COLUMN IF NOT EXISTS "outbound_traffic_usage" bigint NOT NULL DEFAULT 0;
