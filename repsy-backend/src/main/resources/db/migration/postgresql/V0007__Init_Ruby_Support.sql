create table "public"."ruby_gem"
(
    "id"                uuid         not null
        constraint "pk_ruby_gem"
            primary key,
    "repo_id"           uuid         not null
        constraint "fk_ruby_gem__repo_id"
            references "public"."repo"
            on delete cascade,
    "name"              varchar(255) not null,
    "latest"            varchar(64)  not null,
    "versions_checksum" varchar(32),
    "created_at"        timestamp    not null
);

create unique index "ux_ruby_gem__repo_id_name"
    on "public"."ruby_gem" ("repo_id", "name");

create index "ix_ruby_gem__repo_id"
    on "public"."ruby_gem" ("repo_id");


create table "public"."ruby_gem_version"
(
    "id"                   uuid                  not null
        constraint "pk_ruby_gem_version"
            primary key,
    "gem_id"               uuid                  not null
        constraint "fk_ruby_gem_version__gem_id"
            references "public"."ruby_gem"
            on delete cascade,
    "version"              varchar(64)           not null,
    "platform"             varchar(64)           not null,
    "checksum"             varchar(64)           not null,
    "authors"              varchar(512),
    "description"          text,
    "homepage"             varchar(512),
    "required_ruby_version" varchar(64),
    "yanked"               boolean default false not null,
    "created_at"           timestamp             not null
);

create unique index "ux_ruby_gem_version__gem_id_version_platform"
    on "public"."ruby_gem_version" ("gem_id", "version", "platform");

create index "ix_ruby_gem_version__gem_id"
    on "public"."ruby_gem_version" ("gem_id");


create table "public"."ruby_gem_dependency"
(
    "id"             uuid         not null
        constraint "pk_ruby_gem_dependency"
            primary key,
    "gem_version_id" uuid         not null
        constraint "fk_ruby_gem_dependency__gem_version_id"
            references "public"."ruby_gem_version"
            on delete cascade,
    "name"           varchar(255) not null,
    "requirements"   varchar(255) not null,
    "type"           varchar(16)  not null
);

create index "ix_ruby_gem_dependency__gem_version_id"
    on "public"."ruby_gem_dependency" ("gem_version_id");


alter table "public"."repo"
    drop constraint "ch_repo__type";

alter table "public"."repo"
    add constraint "ch_repo__type"
        check ("type" in ('MAVEN', 'NPM', 'PYPI', 'DOCKER', 'GOLANG', 'CARGO', 'HELM', 'NUGET', 'RUBY'));
