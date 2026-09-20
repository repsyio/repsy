-- "anonymous" labels the token Docker hands to callers without credentials (RPS-986).
MERGE INTO "reserved_username" ("username") KEY ("username") VALUES ('anonymous');
