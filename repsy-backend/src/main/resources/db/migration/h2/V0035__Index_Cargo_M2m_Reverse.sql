-- RPS-2112: indexes for the four foreign key columns that had none (the PostgreSQL twin builds them
-- CONCURRENTLY).
CREATE INDEX IF NOT EXISTS "idx_cargo_crate_author__author_id"
    ON "cargo_crate_author" ("author_id");
CREATE INDEX IF NOT EXISTS "idx_cargo_crate_keyword__keyword_id"
    ON "cargo_crate_keyword" ("keyword_id");
CREATE INDEX IF NOT EXISTS "idx_cargo_crate_category__category_id"
    ON "cargo_crate_category" ("category_id");
CREATE INDEX IF NOT EXISTS "idx_key_store__allowed_keyserver_id"
    ON "key_store" ("allowed_keyserver_id");
