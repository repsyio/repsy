-- Deploy-token secrets are high-entropy values, so a fast unsalted SHA-256 digest is sufficient.
CREATE EXTENSION IF NOT EXISTS pgcrypto;

UPDATE "repo_deploy_token"
SET "token" = encode(digest("token", 'sha256'), 'hex')
WHERE "token" LIKE 'rdt-%';
