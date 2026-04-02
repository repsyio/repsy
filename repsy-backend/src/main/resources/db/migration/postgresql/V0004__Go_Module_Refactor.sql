-- Drop denormalized latest_version column; latest is now computed from go_module_version
ALTER TABLE go_module DROP COLUMN IF EXISTS latest_version;

-- Fix created_at: NOT NULL + timezone-aware
ALTER TABLE go_module         ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE go_module         ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';
ALTER TABLE go_module_version ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE go_module_version ALTER COLUMN created_at TYPE timestamptz USING created_at AT TIME ZONE 'UTC';

-- Widen module_path to accommodate long paths (e.g. multi-segment Go paths)
ALTER TABLE go_module ALTER COLUMN module_path TYPE varchar(1024);

-- Recreate unique index with case-insensitive normalisation (upload normalises to lowercase)
DROP INDEX IF EXISTS "ux_go_module__repo_id_module_path";
CREATE UNIQUE INDEX "ux_go_module__repo_id_module_path" ON go_module (repo_id, LOWER(module_path));
