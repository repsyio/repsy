-- RPS-1212: original_name is NOT NULL DEFAULT '' and is always set by CargoCrateServiceImpl at
-- publish time, so in practice no row should be blank. This is a defensive belt-and-braces
-- backfill in case a blank ever slipped in: an empty original_name would serve an empty crate
-- name to cargo, which is worse than today's normalized-name bug.
UPDATE "public"."cargo_crate" SET "original_name" = "name" WHERE "original_name" = '';
