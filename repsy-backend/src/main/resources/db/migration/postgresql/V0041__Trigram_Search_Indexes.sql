-- RPS-2117: trigram GIN indexes for the "name contains q" searches of the panel lists. A btree
-- cannot serve lower(...) LIKE '%q%', so every search scanned its whole repo (and the count query
-- ran single threaded over the same rows). The expressions below are the ones the JPQL of the
-- search repositories emits byte for byte (|| is IMMUTABLE, concat() is only STABLE and cannot be
-- indexed): change one side and the other must follow.
--
-- A term shorter than 3 characters has no trigram and falls back to the repo_id index, as before.
--
-- CREATE INDEX CONCURRENTLY cannot run in a transaction: the companion
-- V0041__Trigram_Search_Indexes.sql.conf sets executeInTransaction=false. A failed build leaves an
-- INVALID index behind, so every statement is IF NOT EXISTS and the migration test asserts
-- pg_index.indisvalid. The extension itself is created by V0040.
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_maven_artifact__group_artifact_trgm"
    ON "maven_artifact" USING gin ((lower("group_name" || ':' || "artifact_name")) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_npm_package__scope_name_trgm"
    ON "npm_package" USING gin ((lower(coalesce("scope" || '/', '') || "name")) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_docker_image__name_trgm"
    ON "docker_image" USING gin ((lower("name")) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_pypi_package__name_trgm"
    ON "pypi_package" USING gin ((lower("name")) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_helm_chart__name_trgm"
    ON "helm_chart" USING gin ((lower("name")) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_ruby_gem__name_trgm"
    ON "ruby_gem" USING gin ((lower("name")) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_go_module__module_path_trgm"
    ON "go_module" USING gin ((lower("module_path")) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_nuget_package__package_id_trgm"
    ON "nuget_package" USING gin ((lower("package_id")) gin_trgm_ops);
CREATE INDEX CONCURRENTLY IF NOT EXISTS "idx_users__username_trgm"
    ON "users" USING gin ((lower("username")) gin_trgm_ops);
