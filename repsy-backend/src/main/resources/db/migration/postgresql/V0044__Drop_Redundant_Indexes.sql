-- RPS-2121: drop the single-column indexes that are the leading column of another index on the same
-- table. V0001 created both "ux_x__a_b" and "idx_x__a" for every protocol table; the unique (or
-- composite) index answers every lookup and every foreign key check the single-column one does, and
-- the single-column one only costs one more index write per insert, update and delete.
--
-- Kept on purpose: idx_docker_image__repo_id and idx_npm_package__repo_id (their uniques are
-- expression indexes, so they do not cover a plain repo_id lookup),
-- idx_docker_manifest_layer__manifest_id, and every index of the dead docker_tag_platform table
-- (its foreign key to docker_tag still needs idx_docker_tag_platform__tag_id until the table is
-- dropped).
--
-- DROP INDEX CONCURRENTLY cannot run in a transaction: the companion
-- V0044__Drop_Redundant_Indexes.sql.conf sets executeInTransaction=false. Every statement is IF
-- EXISTS, so a failure half-way is repaired by running the migration again.
DROP INDEX CONCURRENTLY IF EXISTS "ix_cargo_crate__repo_id"; -- cargo_crate: covered by ux_cargo_crate__repo_id_name
DROP INDEX CONCURRENTLY IF EXISTS "ix_cargo_crate_index__crate_id"; -- cargo_crate_index: covered by ux_cargo_crate_index__crate_id_vers
DROP INDEX CONCURRENTLY IF EXISTS "ix_cargo_crate_meta__crate_id"; -- cargo_crate_meta: covered by ux_cargo_crate_meta__crate_id_version
DROP INDEX CONCURRENTLY IF EXISTS "idx_docker_layer__repo_id"; -- docker_layer: covered by ux_docker_layer__repo_id_digest
DROP INDEX CONCURRENTLY IF EXISTS "idx_docker_manifest_layer__layer_id"; -- docker_manifest_layer: covered by pk_docker_manifest_layer
DROP INDEX CONCURRENTLY IF EXISTS "idx_docker_tag__image_id"; -- docker_tag: covered by ux_docker_tag__image_id_name
DROP INDEX CONCURRENTLY IF EXISTS "idx_go_module__repo_id"; -- go_module: covered by ux_go_module__repo_id_module_path
DROP INDEX CONCURRENTLY IF EXISTS "idx_go_module_version__module_id"; -- go_module_version: covered by ux_go_module_version__module_id_version
DROP INDEX CONCURRENTLY IF EXISTS "ix_helm_chart__repo_id"; -- helm_chart: covered by ux_helm_chart__repo_id_name
DROP INDEX CONCURRENTLY IF EXISTS "ix_helm_chart_version__chart_id"; -- helm_chart_version: covered by ux_helm_chart_version__chart_id_version
DROP INDEX CONCURRENTLY IF EXISTS "ix_helm_oci_blob__repo_id"; -- helm_oci_blob: covered by ux_helm_oci_blob__repo_id_digest
DROP INDEX CONCURRENTLY IF EXISTS "ix_helm_oci_manifest__repo_id"; -- helm_oci_manifest: covered by ux_helm_oci_manifest__repo_id_name_reference
DROP INDEX CONCURRENTLY IF EXISTS "idx_key_store__repo_id"; -- key_store: covered by ux_key_store__repo_id__allowed_keyserver_id
DROP INDEX CONCURRENTLY IF EXISTS "idx_maven_artifact__repo_id"; -- maven_artifact: covered by ux_maven_artifact__repo_id_group_artifact
DROP INDEX CONCURRENTLY IF EXISTS "idx_maven_artifact_version__artifact_id"; -- maven_artifact_version: covered by ux_maven_artifact_version__artifact_id_version_name
DROP INDEX CONCURRENTLY IF EXISTS "idx_npm_package_dist_tag__package_version_id"; -- npm_package_dist_tag: covered by ux_npm_package_dist_tag__version_id_tag_name
DROP INDEX CONCURRENTLY IF EXISTS "idx_npm_package_version__package_id"; -- npm_package_version: covered by ux_npm_package_version__package_id_version
DROP INDEX CONCURRENTLY IF EXISTS "ix_nuget_package__repo_id"; -- nuget_package: covered by ux_nuget_package__repo_id_package_id
DROP INDEX CONCURRENTLY IF EXISTS "ix_nuget_package_version__package_id"; -- nuget_package_version: covered by ux_nuget_package_version__package_id_version
DROP INDEX CONCURRENTLY IF EXISTS "idx_pgp_public_key__repo_id"; -- pgp_public_key: covered by ux_pgp_public_key__repo_id__fingerprint
DROP INDEX CONCURRENTLY IF EXISTS "idx_pypi_package__repo_id"; -- pypi_package: covered by ux_pypi_package__repo_id_normalized_name
DROP INDEX CONCURRENTLY IF EXISTS "idx_pypi_release__package_id"; -- pypi_release: covered by ux_pypi_release__package_id_version
DROP INDEX CONCURRENTLY IF EXISTS "ix_ruby_gem__repo_id"; -- ruby_gem: covered by ux_ruby_gem__repo_id_name
DROP INDEX CONCURRENTLY IF EXISTS "ix_ruby_gem_version__gem_id"; -- ruby_gem_version: covered by ux_ruby_gem_version__gem_id_version_platform
DROP INDEX CONCURRENTLY IF EXISTS "ix_vulnerability_scan__repo_id"; -- vulnerability_scan: covered by ix_vulnerability_scan__repo_id_artifact_name_artifact_version
