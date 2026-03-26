-- Repsy OS - Initial Database Schema - H2

-- USERS
CREATE TABLE IF NOT EXISTS "users" (
                                       "id"            uuid         PRIMARY KEY,
                                       "username"      varchar(25)  NOT NULL,
    "hash"          varchar(128) NOT NULL,
    "salt"          varchar(16)  NOT NULL,
    "role"          varchar(20)  NOT NULL,
    "created_at"    timestamp    NOT NULL,
    "last_login_at" timestamp,
    CONSTRAINT "ch_users__role" CHECK ("role" IN ('USER', 'ADMIN'))
    );

CREATE UNIQUE INDEX "ux_users__username" ON "users" ("username");
CREATE INDEX        "idx_users__role"    ON "users" ("role");

-- RESERVED USERNAMES
CREATE TABLE IF NOT EXISTS "reserved_username" (
                                                   "username" varchar(100) PRIMARY KEY
    );

INSERT INTO "reserved_username" ("username") VALUES
                                                 ('repsy'), ('repo'), ('about'), ('features'), ('product'),
                                                 ('developers'), ('pricing'), ('docs'), ('maven'), ('npm'),
                                                 ('python'), ('docker'), ('nuget'), ('pypi'), ('package'),
                                                 ('registry'), ('artifact'), ('mvn'), ('register'), ('panel'),
                                                 ('login'), ('gradle'), ('sbt'), ('contact'), ('careers'),
                                                 ('status'), ('privacy-policy'), ('terms-and-conditions'),
                                                 ('hub'), ('compile'), ('deploy'), ('deployment'), ('credential'),
                                                 ('publish'), ('config'), ('documentation'), ('testimonial'),
                                                 ('private'), ('distribution'), ('build');

-- REPO
CREATE TABLE IF NOT EXISTS "repo" (
                                      "id"             uuid         PRIMARY KEY,
                                      "name"           varchar(25)  NOT NULL,
    "description"    varchar(500),
    "private_repo"   boolean      NOT NULL DEFAULT true,
    "type"           varchar(20),
    "allow_override" boolean      NOT NULL DEFAULT false,
    "snapshots"      boolean,
    "releases"       boolean,
    "searchable"     boolean      NOT NULL DEFAULT false,
    "disk_usage"     bigint       NOT NULL DEFAULT 0,
    "created_at"     timestamp    NOT NULL,
    CONSTRAINT "ch_repo__type"       CHECK ("type" IN ('MAVEN', 'NPM', 'PYPI', 'DOCKER')),
    CONSTRAINT "ch_repo__disk_usage" CHECK ("disk_usage" >= 0)
    );

CREATE UNIQUE INDEX "ux_repo__name"  ON "repo" ("name");
CREATE INDEX        "idx_repo__type" ON "repo" ("type");

-- REPO DEPLOY TOKEN
CREATE TABLE IF NOT EXISTS "repo_deploy_token" (
                                                   "id"              uuid         PRIMARY KEY,
                                                   "repo_id"         uuid         NOT NULL,
                                                   "username"        varchar(80)  NOT NULL,
    "name"            varchar(150) NOT NULL,
    "description"     varchar(500),
    "token"           varchar(128) NOT NULL,
    "read_only"       boolean      NOT NULL DEFAULT false,
    "expiration_date" timestamp,
    "last_used_at"    timestamp,
    "created_at"      timestamp    NOT NULL,
    "token_duration"  integer,
    CONSTRAINT "fk_repo_deploy_token__repo_id"
    FOREIGN KEY ("repo_id") REFERENCES "repo" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_repo_deploy_token__token"      ON "repo_deploy_token" ("token");
CREATE INDEX        "idx_repo_deploy_token__repo_id"   ON "repo_deploy_token" ("repo_id");

-- NPM PACKAGE
CREATE TABLE IF NOT EXISTS "npm_package" (
                                             "id"         uuid         PRIMARY KEY,
                                             "repo_id"    uuid         NOT NULL,
                                             "scope"      varchar(214),
    "name"       varchar(214) NOT NULL,
    "latest"     varchar(255),
    "created_at" timestamp    NOT NULL,
    CONSTRAINT "fk_npm_package__repo_id"
    FOREIGN KEY ("repo_id") REFERENCES "repo" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_npm_package__repo_id_scope_name" ON "npm_package" ("repo_id", "scope", "name");
CREATE INDEX        "idx_npm_package__repo_id"            ON "npm_package" ("repo_id");
CREATE INDEX        "idx_npm_package__name"               ON "npm_package" ("name");

-- NPM PACKAGE VERSION
CREATE TABLE IF NOT EXISTS "npm_package_version" (
                                                     "id"                   uuid         PRIMARY KEY,
                                                     "package_id"           uuid         NOT NULL,
                                                     "version"              varchar(255) NOT NULL,
    "author_name"          varchar(255),
    "author_email"         varchar(255),
    "author_url"           varchar(255),
    "bugs_url"             varchar(255),
    "bugs_email"           varchar(255),
    "description"          text,
    "homepage"             varchar(255),
    "license"              varchar(255),
    "repository_type"      varchar(255),
    "repository_url"       varchar(255),
    "deprecated"           boolean      NOT NULL DEFAULT false,
    "deprecation_message"  text,
    "created_at"           timestamp    NOT NULL,
    CONSTRAINT "fk_npm_package_version__package_id"
    FOREIGN KEY ("package_id") REFERENCES "npm_package" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_npm_package_version__package_id_version" ON "npm_package_version" ("package_id", "version");
CREATE INDEX        "idx_npm_package_version__package_id"         ON "npm_package_version" ("package_id");

-- NPM PACKAGE KEYWORD
CREATE TABLE IF NOT EXISTS "npm_package_keyword" (
                                                     "id"                 uuid         PRIMARY KEY,
                                                     "package_version_id" uuid         NOT NULL,
                                                     "keyword"            varchar(255) NOT NULL,
    "created_at"         timestamp    NOT NULL,
    CONSTRAINT "fk_npm_package_keyword__package_version_id"
    FOREIGN KEY ("package_version_id") REFERENCES "npm_package_version" ("id") ON DELETE CASCADE
    );

CREATE INDEX "idx_npm_package_keyword__package_version_id" ON "npm_package_keyword" ("package_version_id");
CREATE INDEX "idx_npm_package_keyword__keyword"             ON "npm_package_keyword" ("keyword");

-- NPM PACKAGE DIST TAG
CREATE TABLE IF NOT EXISTS "npm_package_dist_tag" (
                                                      "id"                 uuid         PRIMARY KEY,
                                                      "package_version_id" uuid         NOT NULL,
                                                      "tag_name"           varchar(255) NOT NULL,
    "created_at"         timestamp    NOT NULL,
    CONSTRAINT "fk_npm_package_dist_tag__package_version_id"
    FOREIGN KEY ("package_version_id") REFERENCES "npm_package_version" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_npm_package_dist_tag__version_id_tag_name" ON "npm_package_dist_tag" ("package_version_id", "tag_name");
CREATE INDEX        "idx_npm_package_dist_tag__package_version_id"  ON "npm_package_dist_tag" ("package_version_id");

-- NPM PACKAGE MAINTAINER
CREATE TABLE IF NOT EXISTS "npm_package_maintainer" (
                                                        "id"                 uuid         PRIMARY KEY,
                                                        "package_version_id" uuid         NOT NULL,
                                                        "name"               varchar(255) NOT NULL,
    "email"              varchar(255),
    "url"                varchar(255),
    "created_at"         timestamp    NOT NULL,
    CONSTRAINT "fk_npm_package_maintainer__package_version_id"
    FOREIGN KEY ("package_version_id") REFERENCES "npm_package_version" ("id") ON DELETE CASCADE
    );

CREATE INDEX "idx_npm_package_maintainer__package_version_id" ON "npm_package_maintainer" ("package_version_id");

-- PYPI PACKAGE
CREATE TABLE IF NOT EXISTS "pypi_package" (
                                              "id"              uuid         PRIMARY KEY,
                                              "repo_id"         uuid         NOT NULL,
                                              "name"            varchar(255) NOT NULL,
    "normalized_name" varchar(255) NOT NULL,
    "stable_version"  varchar(255),
    "latest_version"  varchar(255),
    "created_at"      timestamp    NOT NULL,
    CONSTRAINT "fk_pypi_package__repo_id"
    FOREIGN KEY ("repo_id") REFERENCES "repo" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_pypi_package__repo_id_normalized_name" ON "pypi_package" ("repo_id", "normalized_name");
CREATE INDEX        "idx_pypi_package__repo_id"                 ON "pypi_package" ("repo_id");
CREATE INDEX        "idx_pypi_package__name"                    ON "pypi_package" ("name");

-- PYPI RELEASE
CREATE TABLE IF NOT EXISTS "pypi_release" (
                                              "id"                       uuid         PRIMARY KEY,
                                              "package_id"               uuid         NOT NULL,
                                              "version"                  varchar(255),
    "final_release"            boolean      NOT NULL DEFAULT false,
    "pre_release"              boolean      NOT NULL DEFAULT false,
    "post_release"             boolean      NOT NULL DEFAULT false,
    "dev_release"              boolean      NOT NULL DEFAULT false,
    "requires_python"          varchar(255),
    "summary"                  text,
    "home_page"                varchar(255),
    "author"                   varchar(255),
    "author_email"             varchar(255),
    "license"                  varchar(255),
    "description"              text,
    "description_content_type" varchar(255),
    "created_at"               timestamp    NOT NULL,
    CONSTRAINT "fk_pypi_release__package_id"
    FOREIGN KEY ("package_id") REFERENCES "pypi_package" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_pypi_release__package_id_version" ON "pypi_release" ("package_id", "version");
CREATE INDEX        "idx_pypi_release__package_id"         ON "pypi_release" ("package_id");

-- PYPI RELEASE CLASSIFIER
CREATE TABLE IF NOT EXISTS "pypi_release_classifier" (
                                                         "id"         uuid         PRIMARY KEY,
                                                         "release_id" uuid         NOT NULL,
                                                         "classifier" varchar(255) NOT NULL,
    "value"      varchar(255) NOT NULL,
    CONSTRAINT "fk_pypi_release_classifier__release_id"
    FOREIGN KEY ("release_id") REFERENCES "pypi_release" ("id") ON DELETE CASCADE
    );

CREATE INDEX "idx_pypi_release_classifier__release_id" ON "pypi_release_classifier" ("release_id");

-- PYPI RELEASE PROJECT URL
CREATE TABLE IF NOT EXISTS "pypi_release_project_url" (
                                                          "id"         uuid        PRIMARY KEY,
                                                          "release_id" uuid        NOT NULL,
                                                          "label"      varchar(32) NOT NULL,
    "url"        text        NOT NULL,
    CONSTRAINT "fk_pypi_release_project_url__release_id"
    FOREIGN KEY ("release_id") REFERENCES "pypi_release" ("id") ON DELETE CASCADE
    );

CREATE INDEX "idx_pypi_release_project_url__release_id" ON "pypi_release_project_url" ("release_id");

-- MAVEN ARTIFACT
CREATE TABLE IF NOT EXISTS "maven_artifact" (
                                                "id"              uuid         PRIMARY KEY,
                                                "repo_id"         uuid         NOT NULL,
                                                "group_name"      varchar(255) NOT NULL,
    "artifact_name"   varchar(255) NOT NULL,
    "latest"          varchar(255),
    "release"         varchar(255),
    "name"            varchar(255),
    "prefix"          varchar(150),
    "plugin"          boolean      NOT NULL DEFAULT false,
    "packaging"       varchar(50),
    "created_at"      timestamp,
    "last_updated_at" timestamp,
    CONSTRAINT "fk_maven_artifact__repo_id"
    FOREIGN KEY ("repo_id") REFERENCES "repo" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_maven_artifact__repo_id_group_artifact" ON "maven_artifact" ("repo_id", "group_name", "artifact_name");
CREATE INDEX        "idx_maven_artifact__repo_id"                ON "maven_artifact" ("repo_id");
CREATE INDEX        "idx_maven_artifact__group_name"             ON "maven_artifact" ("group_name");
CREATE INDEX        "idx_maven_artifact__artifact_name"          ON "maven_artifact" ("artifact_name");

-- MAVEN ARTIFACT VERSION
CREATE TABLE IF NOT EXISTS "maven_artifact_version" (
                                                        "id"                      uuid         PRIMARY KEY,
                                                        "artifact_id"             uuid         NOT NULL,
                                                        "type"                    varchar(20)  NOT NULL,
    "version_name"            varchar(500) NOT NULL,
    "name"                    varchar(255),
    "description"             text,
    "prefix"                  varchar(150),
    "url"                     varchar(255),
    "organization"            varchar(150),
    "packaging"               varchar(50),
    "source_code_url"         varchar(255),
    "parent_artifact_name"    varchar(255),
    "parent_artifact_version" varchar(255),
    "parent_artifact_group"   varchar(255),
    "has_documents"           boolean      NOT NULL DEFAULT false,
    "has_sources"             boolean      NOT NULL DEFAULT false,
    "has_modules"             boolean      NOT NULL DEFAULT false,
    "signed"                  boolean               DEFAULT false,
    "created_at"              timestamp,
    "last_updated_at"         timestamp,
    CONSTRAINT "fk_maven_artifact_version__artifact_id"
    FOREIGN KEY ("artifact_id") REFERENCES "maven_artifact" ("id") ON DELETE CASCADE,
    CONSTRAINT "ch_maven_artifact_version__type"
    CHECK ("type" IN ('SNAPSHOT', 'RELEASE', 'PLUGIN'))
    );

CREATE UNIQUE INDEX "ux_maven_artifact_version__artifact_id_version_name" ON "maven_artifact_version" ("artifact_id", "version_name");
CREATE INDEX        "idx_maven_artifact_version__artifact_id"               ON "maven_artifact_version" ("artifact_id");

-- MAVEN VERSION LICENSE
CREATE TABLE IF NOT EXISTS "maven_version_license" (
                                                       "id"                  uuid         PRIMARY KEY,
                                                       "artifact_version_id" uuid         NOT NULL,
                                                       "name"                varchar(255) NOT NULL,
    "url"                 varchar(255),
    CONSTRAINT "fk_maven_version_license__artifact_version_id"
    FOREIGN KEY ("artifact_version_id") REFERENCES "maven_artifact_version" ("id") ON DELETE CASCADE
    );

CREATE INDEX "idx_maven_version_license__artifact_version_id" ON "maven_version_license" ("artifact_version_id");

-- MAVEN VERSION DEVELOPER
CREATE TABLE IF NOT EXISTS "maven_version_developer" (
                                                         "id"                  uuid         PRIMARY KEY,
                                                         "artifact_version_id" uuid         NOT NULL,
                                                         "name"                varchar(255) NOT NULL,
    "email"               varchar(255),
    CONSTRAINT "fk_maven_version_developer__artifact_version_id"
    FOREIGN KEY ("artifact_version_id") REFERENCES "maven_artifact_version" ("id") ON DELETE CASCADE
    );

CREATE INDEX "idx_maven_version_developer__artifact_version_id" ON "maven_version_developer" ("artifact_version_id");

-- MAVEN KEY STORE
CREATE TABLE IF NOT EXISTS "key_store" (
                                           "id"         uuid         PRIMARY KEY,
                                           "repo_id"    uuid         NOT NULL,
                                           "url"        varchar(512) NOT NULL,
    "created_at" timestamp    NOT NULL,
    CONSTRAINT "fk_key_store__repo_id"
    FOREIGN KEY ("repo_id") REFERENCES "repo" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_key_store__repo_id"  ON "key_store" ("repo_id");
CREATE INDEX        "idx_key_store__repo_id" ON "key_store" ("repo_id");

-- DOCKER IMAGE
CREATE TABLE IF NOT EXISTS "docker_image" (
                                              "id"              uuid         PRIMARY KEY,
                                              "repo_id"         uuid         NOT NULL,
                                              "name"            varchar(255),
    "size"            bigint                DEFAULT 0,
    "digest"          varchar(255),
    "created_at"      timestamp,
    "last_updated_at" timestamp,
    CONSTRAINT "fk_docker_image__repo_id"
    FOREIGN KEY ("repo_id") REFERENCES "repo" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_docker_image__repo_id_name" ON "docker_image" ("repo_id", "name");
CREATE INDEX        "idx_docker_image__repo_id"      ON "docker_image" ("repo_id");
CREATE INDEX        "idx_docker_image__digest"       ON "docker_image" ("digest");

-- DOCKER TAG
CREATE TABLE IF NOT EXISTS "docker_tag" (
                                            "id"              uuid         PRIMARY KEY,
                                            "image_id"        uuid         NOT NULL,
                                            "name"            varchar(255) NOT NULL,
    "digest"          varchar(255) NOT NULL,
    "media_type"      varchar(255) NOT NULL,
    "platform"        varchar(255) NOT NULL,
    "version"         integer      NOT NULL DEFAULT 0,
    "created_at"      timestamp,
    "last_updated_at" timestamp,
    CONSTRAINT "fk_docker_tag__image_id"
    FOREIGN KEY ("image_id") REFERENCES "docker_image" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_docker_tag__image_id_name_platform" ON "docker_tag" ("image_id", "name", "platform");
CREATE INDEX        "idx_docker_tag__image_id"               ON "docker_tag" ("image_id");
CREATE INDEX        "idx_docker_tag__name"                   ON "docker_tag" ("name");
CREATE INDEX        "idx_docker_tag__digest"                 ON "docker_tag" ("digest");

-- DOCKER TAG PLATFORM
CREATE TABLE IF NOT EXISTS "docker_tag_platform" (
                                                     "id"              uuid         PRIMARY KEY,
                                                     "tag_id"          uuid         NOT NULL,
                                                     "platform"        varchar(255) NOT NULL,
    "version"         integer      NOT NULL DEFAULT 0,
    "created_at"      timestamp,
    "last_updated_at" timestamp,
    CONSTRAINT "fk_docker_tag_platform__tag_id"
    FOREIGN KEY ("tag_id") REFERENCES "docker_tag" ("id") ON DELETE CASCADE
    );

CREATE INDEX "idx_docker_tag_platform__tag_id"          ON "docker_tag_platform" ("tag_id");
CREATE INDEX "idx_docker_tag_platform__tag_id_platform" ON "docker_tag_platform" ("tag_id", "platform");

-- NOTE: H2 does not support partial indexes. Full unique index used instead.
CREATE UNIQUE INDEX "ux_docker_tag_platform__tag_id_platform"
    ON "docker_tag_platform" ("tag_id", "platform");

-- DOCKER MANIFEST
CREATE TABLE IF NOT EXISTS "docker_manifest" (
                                                 "id"                uuid         PRIMARY KEY,
                                                 "tag_platform_id"   uuid,
                                                 "name"              varchar(255) NOT NULL,
    "platform"          varchar(255) NOT NULL,
    "digest"            varchar(255) NOT NULL,
    "media_type"        varchar(255) NOT NULL,
    "config_media_type" varchar(255),
    "config_digest"     varchar(255),
    "schema_version"    integer      NOT NULL,
    "config_size"       bigint                DEFAULT 0,
    "version"           integer      NOT NULL DEFAULT 0,
    "created_at"        timestamp,
    "last_updated_at"   timestamp,
    CONSTRAINT "fk_docker_manifest__tag_platform_id"
    FOREIGN KEY ("tag_platform_id") REFERENCES "docker_tag_platform" ("id") ON DELETE CASCADE
    );

CREATE INDEX "idx_docker_manifest__tag_platform_id" ON "docker_manifest" ("tag_platform_id");
CREATE INDEX "idx_docker_manifest__digest"           ON "docker_manifest" ("digest");

-- NOTE: H2 does not support partial indexes. Full unique index used instead.
CREATE UNIQUE INDEX "ux_docker_manifest__tag_platform_digest"
    ON "docker_manifest" ("tag_platform_id", "digest");

-- DOCKER LAYER
CREATE TABLE IF NOT EXISTS "docker_layer" (
                                              "id"              uuid         PRIMARY KEY,
                                              "repo_id"         uuid         NOT NULL,
                                              "digest"          varchar(255) NOT NULL,
    "size"            bigint       NOT NULL DEFAULT 0,
    "media_type"      varchar(255) NOT NULL,
    "created_at"      timestamp,
    "last_updated_at" timestamp,
    CONSTRAINT "fk_docker_layer__repo_id"
    FOREIGN KEY ("repo_id") REFERENCES "repo" ("id") ON DELETE CASCADE
    );

CREATE UNIQUE INDEX "ux_docker_layer__repo_id_digest" ON "docker_layer" ("repo_id", "digest");
CREATE INDEX        "idx_docker_layer__repo_id"        ON "docker_layer" ("repo_id");
CREATE INDEX        "idx_docker_layer__digest"         ON "docker_layer" ("digest");

-- DOCKER MANIFEST LAYER
CREATE TABLE IF NOT EXISTS "docker_manifest_layer" (
                                                       "manifest_id" uuid NOT NULL,
                                                       "layer_id"    uuid NOT NULL,
                                                       PRIMARY KEY ("manifest_id", "layer_id"),
    CONSTRAINT "fk_docker_manifest_layer__manifest_id"
    FOREIGN KEY ("manifest_id") REFERENCES "docker_manifest" ("id") ON DELETE CASCADE,
    CONSTRAINT "fk_docker_manifest_layer__layer_id"
    FOREIGN KEY ("layer_id") REFERENCES "docker_layer" ("id") ON DELETE CASCADE
    );

CREATE INDEX        "idx_docker_manifest_layer__manifest_id" ON "docker_manifest_layer" ("manifest_id");
CREATE INDEX        "idx_docker_manifest_layer__layer_id"    ON "docker_manifest_layer" ("layer_id");
CREATE UNIQUE INDEX "pk_docker_manifest_layer"               ON "docker_manifest_layer" ("layer_id", "manifest_id");
