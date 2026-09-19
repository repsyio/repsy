-- Deploy-token secrets are high-entropy values, so a fast unsalted SHA-256 digest is sufficient.
UPDATE "repo_deploy_token"
SET "token" = RAWTOHEX(HASH('SHA256', STRINGTOUTF8("token")));
