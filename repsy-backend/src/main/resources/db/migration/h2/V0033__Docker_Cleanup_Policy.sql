-- RPS-1882: the Docker cleanup policy of a repo, ported from Repsy Cloud. One row per repo, created
-- disabled on first read, so nothing is deleted until a manager of the repo enables it.
CREATE TABLE IF NOT EXISTS "docker_cleanup_policy" (
    "repo_id"         uuid         PRIMARY KEY,
    "cadence"         varchar(32)  NOT NULL,
    "name_regex"      text         NOT NULL DEFAULT '.*',
    "name_regex_keep" text,
    "keep_last_n"     integer      NOT NULL DEFAULT 10,
    "keep_days"       integer      NOT NULL DEFAULT 7,
    "enabled"         boolean      NOT NULL DEFAULT FALSE,
    "last_run_at"     timestamp,
    "next_run_at"     timestamp,
    "created_at"      timestamp    NOT NULL,
    "updated_at"      timestamp    NOT NULL,
    CONSTRAINT "fk_docker_cleanup_policy__repo_id"
    FOREIGN KEY ("repo_id") REFERENCES "repo" ("id") ON DELETE CASCADE
    );

CREATE INDEX "idx_docker_cleanup_policy__due" ON "docker_cleanup_policy" ("enabled", "next_run_at");
