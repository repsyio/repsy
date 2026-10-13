-- RPS-2198: pypi_release.version is NOT NULL (no code path ever wrote a null version). Same
-- statement as the PostgreSQL V0045 without lock_timeout, which H2 does not have.
ALTER TABLE "pypi_release" ALTER COLUMN "version" SET NOT NULL;
