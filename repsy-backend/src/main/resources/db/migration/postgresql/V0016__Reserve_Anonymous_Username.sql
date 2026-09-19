-- "anonymous" labels the token Docker hands to callers without credentials (RPS-986).
INSERT INTO "reserved_username" ("username") VALUES ('anonymous') ON CONFLICT DO NOTHING;
