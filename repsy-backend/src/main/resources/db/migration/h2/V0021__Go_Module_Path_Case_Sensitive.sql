-- RPS-1232: Go module paths are now stored decoded and case-preserved rather than lower-cased --
-- see the matching PostgreSQL migration. H2 does not support function-based indexes, so
-- V0002__Golang_Protocol.sql already created "ux_go_module__repo_id_module_path" as a plain,
-- case-sensitive index on module_path (its own comment, "service layer normalises module_path to
-- lowercase", is now stale: the service layer no longer does that). This file is a deliberate no-op,
-- kept only so the migration version numbers line up across both databases.
CREATE UNIQUE INDEX IF NOT EXISTS "ux_go_module__repo_id_module_path" ON "go_module" ("repo_id", "module_path");
