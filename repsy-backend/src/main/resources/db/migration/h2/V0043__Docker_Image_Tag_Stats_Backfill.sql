-- RPS-2120: backfill the V0042 columns of the existing Docker images (see the PostgreSQL twin). An
-- embedded H2 instance holds few images, so one statement does it.
UPDATE "docker_image" i
SET "tag_count" = (SELECT count(*) FROM "docker_tag" t WHERE t."image_id" = i."id"),
    "last_tag_at" = (SELECT max(t."created_at") FROM "docker_tag" t WHERE t."image_id" = i."id");
