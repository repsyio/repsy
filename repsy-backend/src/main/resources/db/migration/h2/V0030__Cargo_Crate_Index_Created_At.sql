-- RPS-1605: the sparse index is served in publish order, so each index row needs a publish time.
-- Backfill from the publish time cargo_crate_meta already keeps for the same version (set in the
-- same transaction as the index row); a row without a meta row falls back to its crate's time.
ALTER TABLE "cargo_crate_index" ADD COLUMN "created_at" timestamp;

UPDATE "cargo_crate_index" AS i
SET "created_at" = COALESCE(
    (SELECT m."created_at"
       FROM "cargo_crate_meta" m
      WHERE m."crate_id" = i."crate_id" AND m."version" = i."vers"),
    (SELECT c."created_at" FROM "cargo_crate" c WHERE c."id" = i."crate_id"));

ALTER TABLE "cargo_crate_index" ALTER COLUMN "created_at" SET NOT NULL;
