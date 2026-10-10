-- RPS-2117: the pg_trgm extension, which the trigram indexes of V0041 need. It is a file of its own:
-- the indexes are built CONCURRENTLY outside a transaction, and such a file holds only index
-- statements.
--
-- pg_trgm is a trusted extension (PostgreSQL 13 and later), so a role with CREATE on the database
-- may install it, no superuser needed. A customer-run database that lacks the contrib package or
-- the privilege fails here with a message that says what to do, instead of at the first index.
DO
$$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'pg_trgm') THEN
            IF NOT EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'pg_trgm') THEN
                RAISE EXCEPTION 'RPS-2117: the PostgreSQL extension pg_trgm is not available on this server. '
                    'Install the contrib package of your PostgreSQL distribution (the postgres:18 image has it).';
            END IF;
            IF NOT (has_database_privilege(current_user, current_database(), 'CREATE')
                OR (SELECT rolsuper FROM pg_roles WHERE rolname = current_user)) THEN
                RAISE EXCEPTION 'RPS-2117: role % may not create the extension pg_trgm. '
                    'Run "CREATE EXTENSION pg_trgm" in database % as a superuser or grant CREATE on the database.',
                    current_user, current_database();
            END IF;
        END IF;
    END
$$;

CREATE EXTENSION IF NOT EXISTS pg_trgm;
