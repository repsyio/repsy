-- RPS-1232: Go module paths are now stored decoded and case-preserved rather than lower-cased
-- (AbstractGoProtocolFacade no longer calls toLowerCase() on the decoded path), matching the real
-- `go` toolchain's own case-sensitive module-path identity. The uniqueness index created in
-- V0002__Golang_Protocol.sql enforced case-INsensitive uniqueness (LOWER(module_path)), which would
-- now incorrectly refuse "github.com/Foo" and "github.com/foo" as the same module. Replace it with a
-- plain, case-sensitive unique index on module_path itself.
DROP INDEX IF EXISTS "ux_go_module__repo_id_module_path";
CREATE UNIQUE INDEX "ux_go_module__repo_id_module_path" ON "go_module" ("repo_id", "module_path");
