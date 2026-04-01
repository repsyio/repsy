ALTER TABLE "go_module_version"
    ADD COLUMN "mod_hash" varchar(100),
    ADD COLUMN "zip_hash" varchar(100);
