-- Golang Protocol Support

MERGE INTO "reserved_username" ("username") KEY ("username") VALUES ('go');
MERGE INTO "reserved_username" ("username") KEY ("username") VALUES ('golang');

-- Add GOLANG to repo type constraint
ALTER TABLE "repo" DROP CONSTRAINT IF EXISTS "ch_repo__type";
ALTER TABLE "repo" ADD CONSTRAINT "ch_repo__type" CHECK ("type" IN ('MAVEN', 'NPM', 'PYPI', 'DOCKER', 'GOLANG'));

-- GO MODULE
CREATE TABLE IF NOT EXISTS "go_module" (
    "id"          uuid                     PRIMARY KEY,
    "repo_id"     uuid                     NOT NULL,
    "module_path" varchar(1024)            NOT NULL,
    "created_at"  timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT "fk_go_module__repo_id"
    FOREIGN KEY ("repo_id") REFERENCES "repo" ("id") ON DELETE CASCADE
);

-- H2 does not support function-based indexes; service layer normalises module_path to lowercase
CREATE UNIQUE INDEX IF NOT EXISTS "ux_go_module__repo_id_module_path" ON "go_module" ("repo_id", "module_path");
CREATE INDEX IF NOT EXISTS        "idx_go_module__repo_id"             ON "go_module" ("repo_id");
CREATE INDEX IF NOT EXISTS        "idx_go_module__module_path"         ON "go_module" ("module_path");

-- GO MODULE VERSION
CREATE TABLE IF NOT EXISTS "go_module_version" (
    "id"         uuid                     PRIMARY KEY,
    "module_id"  uuid                     NOT NULL,
    "version"    varchar(100)             NOT NULL,
    "go_version" varchar(20),
    "mod_hash"   varchar(100),
    "zip_hash"   varchar(100),
    "deleted"    boolean      NOT NULL DEFAULT false,
    "created_at" timestamp with time zone NOT NULL DEFAULT now(),
    CONSTRAINT "fk_go_module_version__module_id"
    FOREIGN KEY ("module_id") REFERENCES "go_module" ("id") ON DELETE CASCADE
);

CREATE UNIQUE INDEX IF NOT EXISTS "ux_go_module_version__module_id_version" ON "go_module_version" ("module_id", "version");
CREATE INDEX IF NOT EXISTS        "idx_go_module_version__module_id"         ON "go_module_version" ("module_id");
