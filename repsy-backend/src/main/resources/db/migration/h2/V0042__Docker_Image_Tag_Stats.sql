-- RPS-2120: the stored tag statistics of a Docker image (see the PostgreSQL twin).
ALTER TABLE "docker_image" ADD COLUMN IF NOT EXISTS "last_tag_at" timestamp;
ALTER TABLE "docker_image" ADD COLUMN IF NOT EXISTS "tag_count" integer NOT NULL DEFAULT 0;
