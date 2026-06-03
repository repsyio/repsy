create table "public"."nuget_package"
(
    "id"         uuid         not null
        constraint "pk_nuget_package"
            primary key,
    "repo_id"    uuid         not null,
    "package_id" varchar(256) not null,
    "created_at" timestamp    not null,
    "updated_at" timestamp
);

alter table "public"."nuget_package"
    add constraint "fk_nuget_package__repo_id"
        foreign key ("repo_id") references "public"."repo" ("id") on delete cascade;

create unique index "ux_nuget_package__repo_id_package_id"
    on "public"."nuget_package" ("repo_id", "package_id");

create index "ix_nuget_package__repo_id"
    on "public"."nuget_package" ("repo_id");


create table "public"."nuget_package_version"
(
    "id"              uuid         not null
        constraint "pk_nuget_package_version"
            primary key,
    "package_id"      uuid         not null
        constraint "fk_nuget_package_version__package_id"
            references "public"."nuget_package"
            on delete cascade,
    "version"         varchar(64)  not null,
    "is_prerelease"   boolean      not null default false,
    "is_listed"       boolean      not null default true,
    "published_at"    timestamp    not null,
    "download_count"  bigint       not null default 0,
    "title"           varchar(512),
    "description"     clob,
    "authors"         clob,
    "tags"            varchar(1024),
    "icon_url"        varchar(512),
    "license_url"     varchar(512),
    "project_url"     varchar(512),
    "repository_url"  varchar(512),
    "readme"          clob,
    "dependencies"    clob,
    "created_at"      timestamp    not null
);

create unique index "ux_nuget_package_version__package_id_version"
    on "public"."nuget_package_version" ("package_id", "version");

create index "ix_nuget_package_version__package_id"
    on "public"."nuget_package_version" ("package_id");

alter table "public"."repo"
drop constraint "ch_repo__type";

alter table "public"."repo"
    add constraint "ch_repo__type"
        check ("type" in ('MAVEN', 'NPM', 'PYPI', 'DOCKER', 'GOLANG', 'CARGO', 'NUGET'));
