-- RPS-1615: users.salt holds the salt of a pre-BCrypt SHA-256 password hash until its owner's first
-- login replaces the hash with BCrypt, which keeps its own salt. New hashes carry no salt, so the
-- column is nullable. A database that never ran the old V0017 (all released ones) still has it and
-- only loses the NOT NULL; one that did run it gets the column back, empty. The column is added
-- from a check and not with ADD COLUMN IF NOT EXISTS, which would log a WARN on every upgrade.
DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
      FROM information_schema.columns
     WHERE table_schema = current_schema()
       AND table_name = 'users'
       AND column_name = 'salt') THEN
    ALTER TABLE "users" ADD COLUMN "salt" varchar(16);
  END IF;
END
$$;

ALTER TABLE "users" ALTER COLUMN "salt" DROP NOT NULL;
