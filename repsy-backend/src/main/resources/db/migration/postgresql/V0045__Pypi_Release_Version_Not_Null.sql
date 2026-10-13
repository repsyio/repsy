-- RPS-2198: pypi_release.version is NOT NULL. V0001 created it nullable, but no code path ever
-- wrote a null (ReleaseVersion.of(...) always yields a value) and the unique index
-- ux_pypi_release__package_id_version is meant to identify a release by it. A production count
-- (Cloud, read-only) found 0 null rows out of 9711, so there is nothing to backfill and no row is
-- deleted or rewritten: SET NOT NULL only scans the table once under the lock. It is the fail-safe
-- choice: if a null row exists in some database the statement fails with "contains null values"
-- and the migration is not recorded, instead of silently inventing a version for it.
SET lock_timeout = '5s';

ALTER TABLE "pypi_release" ALTER COLUMN "version" SET NOT NULL;
