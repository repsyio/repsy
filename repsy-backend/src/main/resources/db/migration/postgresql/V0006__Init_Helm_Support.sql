create table "public"."helm_chart"
(
    "id"              uuid         not null
        constraint "pk_helm_chart"
            primary key,
    "version_lock"    integer      not null,
    "repo_id"         uuid         not null
        constraint "fk_helm_chart__repo_id"
            references "public"."repo"
            on delete cascade,
    "name"            varchar(255) not null,
    "version"         varchar(64)  not null,
    "description"     text,
    "app_version"     varchar(64),
    "digest"          varchar(71)  not null,
    "size"            bigint       not null,
    "type"            varchar(32),
    "created_at"      timestamp    not null,
    "last_updated_at" timestamp
);

create unique index "ux_helm_chart__repo_id_name_version"
    on "public"."helm_chart" ("repo_id", "name", "version");

create index "ix_helm_chart__repo_id"
    on "public"."helm_chart" ("repo_id");

create table "public"."helm_oci_blob"
(
    "id"              uuid         not null
        constraint "pk_helm_oci_blob"
            primary key,
    "version_lock"    integer      not null,
    "repo_id"         uuid         not null
        constraint "fk_helm_oci_blob__repo_id"
            references "public"."repo"
            on delete cascade,
    "digest"          varchar(71)  not null,
    "size"            bigint       not null,
    "media_type"      varchar(255) not null,
    "created_at"      timestamp    not null,
    "last_updated_at" timestamp
);

create unique index "ux_helm_oci_blob__repo_id_digest"
    on "public"."helm_oci_blob" ("repo_id", "digest");

create index "ix_helm_oci_blob__repo_id"
    on "public"."helm_oci_blob" ("repo_id");

create table "public"."helm_oci_manifest"
(
    "id"              uuid         not null
        constraint "pk_helm_oci_manifest"
            primary key,
    "version_lock"    integer      not null,
    "repo_id"         uuid         not null
        constraint "fk_helm_oci_manifest__repo_id"
            references "public"."repo"
            on delete cascade,
    "chart_id"        uuid         not null
        constraint "fk_helm_oci_manifest__chart_id"
            references "public"."helm_chart"
            on delete cascade,
    "name"            varchar(255) not null,
    "reference"       varchar(255) not null,
    "digest"          varchar(71)  not null,
    "media_type"      varchar(255) not null,
    "content"         text         not null,
    "created_at"      timestamp    not null,
    "last_updated_at" timestamp
);

create unique index "ux_helm_oci_manifest__repo_id_name_reference"
    on "public"."helm_oci_manifest" ("repo_id", "name", "reference");

create index "ix_helm_oci_manifest__repo_id"
    on "public"."helm_oci_manifest" ("repo_id");

create index "ix_helm_oci_manifest__chart_id"
    on "public"."helm_oci_manifest" ("chart_id");

alter table "public"."repo"
    drop constraint "ch_repo__type";

alter table "public"."repo"
    add constraint "ch_repo__type"
        check ("type" in ('MAVEN', 'NPM', 'PYPI', 'DOCKER', 'GOLANG', 'CARGO', 'NUGET', 'HELM'));
