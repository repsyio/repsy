-- Drop denormalized latest_version column
ALTER TABLE go_module DROP COLUMN IF EXISTS latest_version;

-- Add publication status to every version record
ALTER TABLE go_module_version ADD COLUMN IF NOT EXISTS "status" varchar(20) NOT NULL DEFAULT 'PUBLISHED';

-- Fix created_at: NOT NULL + timezone-aware (H2 uses TIMESTAMP WITH TIME ZONE)
ALTER TABLE go_module         ALTER COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL;
ALTER TABLE go_module_version ALTER COLUMN created_at TIMESTAMP WITH TIME ZONE NOT NULL;

-- Widen module_path
ALTER TABLE go_module ALTER COLUMN module_path varchar(1024) NOT NULL;

-- Recreate unique index (H2 does not support function-based indexes; service layer normalises to lowercase)
DROP INDEX IF EXISTS "ux_go_module__repo_id_module_path";
CREATE UNIQUE INDEX IF NOT EXISTS "ux_go_module__repo_id_module_path" ON go_module (repo_id, module_path);
