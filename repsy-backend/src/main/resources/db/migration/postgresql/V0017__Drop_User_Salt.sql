-- RPS-1615 (was RPS-1033): this migration does nothing on purpose.
--
-- It used to blank the hash of every account that still had a salted SHA-256 hash (every account of
-- release v26.08.4, which predates BCrypt) and to drop users.salt, so an upgrade reset every password.
-- The application now verifies such a hash once and replaces it with BCrypt on the account's first
-- successful login (PasswordHasher, RPS-1615), so the hashes and the salt column must stay. The
-- version is kept, with a harmless statement, so the numbering of the migrations does not change.
-- A database that already ran the old script (never a released one) has lost both, and V0031 only
-- restores the column; see the README.
SELECT 1;
