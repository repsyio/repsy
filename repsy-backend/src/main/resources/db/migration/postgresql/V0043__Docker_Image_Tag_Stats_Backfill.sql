-- RPS-2120: backfill the V0042 columns of the existing Docker images.
--
-- docker_image is one of the larger tables of an instance with Docker repos, so the backfill runs
-- in id-range batches of 5000 rows with a COMMIT after each one: no transaction holds the row
-- locks of the whole table, and a failure keeps the batches already done (every batch recomputes
-- its values from scratch, so running it again is harmless). COMMIT inside a DO block needs it to
-- run outside a transaction: the companion V0043__Docker_Image_Tag_Stats_Backfill.sql.conf sets
-- executeInTransaction=false. The tag lookups use idx_docker_tag__image_id.
DO $$
DECLARE
    batch_start uuid := '00000000-0000-0000-0000-000000000000';
    batch_end uuid;
BEGIN
    LOOP
        -- PostgreSQL has no max(uuid): the last id of the batch is the first in descending order.
        SELECT b."id" INTO batch_end
        FROM (
            SELECT "id" FROM "docker_image"
            WHERE "id" > batch_start
            ORDER BY "id"
            LIMIT 5000
        ) b
        ORDER BY b."id" DESC
        LIMIT 1;

        EXIT WHEN batch_end IS NULL;

        UPDATE "docker_image" i
        SET "tag_count" = (
                SELECT count(*) FROM "docker_tag" t WHERE t."image_id" = i."id"),
            "last_tag_at" = (
                SELECT max(t."created_at") FROM "docker_tag" t WHERE t."image_id" = i."id")
        WHERE i."id" > batch_start AND i."id" <= batch_end;

        batch_start := batch_end;
        COMMIT;
    END LOOP;
END
$$;
