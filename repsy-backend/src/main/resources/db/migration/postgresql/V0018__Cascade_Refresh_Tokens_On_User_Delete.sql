-- RPS-1082: delete a user's refresh tokens together with the user.
--
-- refresh_tokens.user_id (V0015) had no foreign key, so the rows of a deleted account stayed until
-- RefreshTokenService.purgeExpired removed them after expires_at. Rows whose user is already gone
-- (tokens of accounts deleted before this migration) are removed first, or the constraint could not
-- be added; they were unusable anyway, as a token of a missing user is rejected.
DELETE FROM "public"."refresh_tokens"
WHERE "user_id" NOT IN (SELECT "id" FROM "public"."users");

-- V0015 indexes only family_id and expires_at. Without this index every user deleted through the
-- cascade would scan the whole table.
CREATE INDEX "idx_refresh_tokens_user_id" ON "public"."refresh_tokens" ("user_id");

ALTER TABLE "public"."refresh_tokens"
    ADD CONSTRAINT "fk_refresh_tokens__user_id"
        FOREIGN KEY ("user_id") REFERENCES "public"."users" ("id") ON DELETE CASCADE;
