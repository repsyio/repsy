create table "public"."cargo_crate"
(
    "id"              uuid         not null
        constraint "pk_cargo_crate"
            primary key,
    "repo_id"         uuid         not null,
    "name"            varchar(64)  not null,
    "original_name"   varchar(64)  default '' not null,
    "max_version"     varchar(64)  default '' not null,
    "total_downloads" bigint       default 0  not null,
    "description"     clob,
    "homepage"        varchar(255),
    "repository"      varchar(255),
    "created_at"      timestamp    not null,
    "has_lib"         boolean      not null,
    "last_updated_at" timestamp
);

alter table "public"."cargo_crate"
    add constraint "fk_cargo_crate__repo_id"
        foreign key ("repo_id") references "public"."repo" ("id") on delete cascade;

create unique index "ux_cargo_crate__repo_id_name"
    on "public"."cargo_crate" ("repo_id", "name");

create index "ix_cargo_crate__repo_id"
    on "public"."cargo_crate" ("repo_id");


create table "public"."cargo_crate_index"
(
    "id"           uuid                  not null
        constraint "pk_cargo_crate_index"
            primary key,
    "crate_id"     uuid                  not null
        constraint "fk_cargo_crate_index__crate_id"
            references "public"."cargo_crate"
            on delete cascade,
    "name"         varchar(255)          not null,
    "vers"         varchar(64)           not null,
    "deps"         clob,
    "cksum"        varchar(255)          not null,
    "features"     clob,
    "features2"    clob,
    "yanked"       boolean default false not null,
    "links"        varchar(255),
    "v"            integer default 1     not null,
    "rust_version" varchar(20)
);

create unique index "ux_cargo_crate_index__crate_id_vers"
    on "public"."cargo_crate_index" ("crate_id", "vers");

create index "ix_cargo_crate_index__crate_id"
    on "public"."cargo_crate_index" ("crate_id");

create index "ix_cargo_crate_index__name"
    on "public"."cargo_crate_index" ("name");


create table "public"."cargo_crate_meta"
(
    "id"            uuid             not null
        constraint "pk_cargo_crate_meta"
            primary key,
    "crate_id"      uuid             not null
        constraint "fk_cargo_crate_meta__crate_id"
            references "public"."cargo_crate"
            on delete cascade,
    "version"       varchar(64)      not null,
    "readme"        clob,
    "license"       varchar(255),
    "license_file"  varchar(255),
    "documentation" varchar(255),
    "edition"       varchar(10),
    "rust_version"  varchar(20),
    "downloads"     bigint default 0 not null,
    "created_at"    timestamp        not null
);

create unique index "ux_cargo_crate_meta__crate_id_version"
    on "public"."cargo_crate_meta" ("crate_id", "version");

create index "ix_cargo_crate_meta__crate_id"
    on "public"."cargo_crate_meta" ("crate_id");


create table "public"."cargo_author"
(
    "id"     uuid         not null
        constraint "pk_cargo_author"
            primary key,
    "author" varchar(255) not null
        constraint "ux_cargo_author__author"
            unique
);

create table "public"."cargo_keyword"
(
    "id"      uuid         not null
        constraint "pk_cargo_keyword"
            primary key,
    "keyword" varchar(100) not null
        constraint "ux_cargo_keyword__keyword"
            unique
);

create table "public"."cargo_category"
(
    "id"       uuid         not null
        constraint "pk_cargo_category"
            primary key,
    "category" varchar(255) not null
        constraint "ux_cargo_category__category"
            unique
);

create table "public"."cargo_crate_author"
(
    "crate_id"  uuid not null
        constraint "fk_cargo_crate_author__crate_id"
            references "public"."cargo_crate"
            on delete cascade,
    "author_id" uuid not null
        constraint "fk_cargo_crate_author__author_id"
            references "public"."cargo_author"
            on delete cascade
);

create unique index "ux_cargo_crate_author__crate_id_author_id"
    on "public"."cargo_crate_author" ("crate_id", "author_id");


create table "public"."cargo_crate_keyword"
(
    "crate_id"   uuid not null
        constraint "fk_cargo_crate_keyword__crate_id"
            references "public"."cargo_crate"
            on delete cascade,
    "keyword_id" uuid not null
        constraint "fk_cargo_crate_keyword__keyword_id"
            references "public"."cargo_keyword"
            on delete cascade
);

create unique index "ux_cargo_crate_keyword__crate_id_keyword_id"
    on "public"."cargo_crate_keyword" ("crate_id", "keyword_id");


create table "public"."cargo_crate_category"
(
    "crate_id"    uuid not null
        constraint "fk_cargo_crate_category__crate_id"
            references "public"."cargo_crate"
            on delete cascade,
    "category_id" uuid not null
        constraint "fk_cargo_crate_category__category_id"
            references "public"."cargo_category"
            on delete cascade
);

create unique index "ux_cargo_crate_category__crate_id_category_id"
    on "public"."cargo_crate_category" ("crate_id", "category_id");

alter table "public"."repo"
drop constraint "ch_repo__type";

alter table "public"."repo"
    add constraint "ch_repo__type"
        check ("type" in ('MAVEN', 'NPM', 'PYPI', 'DOCKER', 'GOLANG' ,'CARGO'));
