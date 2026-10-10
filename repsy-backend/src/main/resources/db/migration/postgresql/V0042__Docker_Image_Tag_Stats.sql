-- RPS-2120: store what the Docker image list shows of an image's tags instead of computing it per
-- row with two correlated subqueries (max(created_at) and count(*) over docker_tag), which made
-- "sort by updatedAt" scan the tags of every image of the repo.
--
-- last_tag_at: the newest created_at of the image's tags, null while it has no tag. It is the
--              value the list shows as updatedAt (falling back to last_updated_at when null).
-- tag_count:   the number of tags of the image; 0 for an image with none.
--
-- Both are written by ImageRepository#refreshTagStats in the transaction that changes the image's
-- tags (ImageTxService#refreshImageSize). ADD COLUMN with a constant default is catalog-only on
-- PostgreSQL 11+, so there is no table rewrite; V0043 backfills the existing rows in batches.
SET lock_timeout = '5s';

ALTER TABLE "docker_image"
    ADD COLUMN IF NOT EXISTS "last_tag_at" timestamp,
    ADD COLUMN IF NOT EXISTS "tag_count" integer NOT NULL DEFAULT 0;
