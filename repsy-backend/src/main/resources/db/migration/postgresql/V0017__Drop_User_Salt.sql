-- RPS-1033: retire the salted SHA-256 password hashes that RPS-961 replaced with BCrypt.
--
-- A SHA-256 hash cannot be converted to BCrypt without the plain-text password, so every account
-- that still has one gets the empty hash, the marker of a password reset. The application no longer
-- verifies such a hash. On startup AdminUserInitializer generates a new password for each admin with
-- an empty hash and logs it ("Admin password has been reset ..."); an admin resets the password of
-- any other user from the users page. Refresh tokens of the affected accounts are revoked, as for
-- any password reset. A BCrypt hash always starts with its algorithm id ("{bcrypt}"), so those rows
-- are left alone.
UPDATE "users"
SET "hash" = '', "token_version" = "token_version" + 1
WHERE "hash" NOT LIKE '{%';

-- BCrypt keeps its salt inside the hash, so the column has no reader left.
ALTER TABLE "users" DROP COLUMN "salt";
