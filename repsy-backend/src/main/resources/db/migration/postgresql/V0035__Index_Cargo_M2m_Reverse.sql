-- RPS-2112: indexes for the four foreign key columns that had none. The unique indexes of the
-- cargo join tables lead with crate_id, so a delete of an author, keyword or category (and of an
-- allowed keyserver) had to scan the whole child table once per deleted row for the ON DELETE
-- CASCADE check. Every other foreign key column of the schema is already indexed.
--
-- CREATE INDEX CONCURRENTLY cannot run in a transaction: the companion
-- V0035__Index_Cargo_M2m_Reverse.sql.conf sets executeInTransaction=false. A failed build leaves an
-- INVALID index behind, so every statement is IF NOT EXISTS and the migration test asserts
-- pg_index.indisvalid.
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_cargo_crate_author__author_id"
    ON "cargo_crate_author" ("author_id");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_cargo_crate_keyword__keyword_id"
    ON "cargo_crate_keyword" ("keyword_id");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_cargo_crate_category__category_id"
    ON "cargo_crate_category" ("category_id");
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_key_store__allowed_keyserver_id"
    ON "key_store" ("allowed_keyserver_id");
