create table cargo_crate
(
    id              uuid                                       not null
        constraint pk_cargo_crate
            primary key,
    repo_id         bigint                                     not null
        constraint fk_cargo_crate__repo_id
            references "repo"
            on delete cascade
        constraint ch_cargo_crate__repo_id
            check (repo_id > 0),
    name            varchar(64)                                not null,
    original_name   varchar(64)  default ''::character varying not null,
    max_version     varchar(64)  default ''::character varying not null,
    total_downloads bigint       default 0                     not null,
    description     text,
    homepage        varchar(255),
    repository      varchar(255),
    e_tag           varchar(64)  default ''::character varying not null,
    last_updated    varchar(255) default ''::character varying not null,
    created_at      timestamp                                  not null,
    last_updated_at timestamp
);

create unique index ux_cargo_crate__repo_id_name
    on cargo_crate (repo_id, name);

create index ix_cargo_crate__repo_id
    on cargo_crate (repo_id);


create table cargo_crate_index
(
    id           uuid                  not null
        constraint pk_cargo_crate_index
            primary key,
    crate_id     uuid                  not null
        constraint fk_cargo_crate_index__crate_id
            references cargo_crate
            on delete cascade,
    name         text                  not null,
    vers         varchar(64)           not null,
    deps         jsonb,
    cksum        text                  not null,
    features     jsonb,
    features2    jsonb,
    yanked       boolean default false not null,
    links        text,
    v            integer default 1     not null,
    rust_version varchar(20)
);

create unique index ux_cargo_crate_index__crate_id_vers
    on cargo_crate_index (crate_id, vers);

create index ix_cargo_crate_index__crate_id
    on cargo_crate_index (crate_id);

create index ix_cargo_crate_index__name
    on cargo_crate_index (name);


create table cargo_crate_meta
(
    id            uuid             not null
        constraint pk_cargo_crate_meta
            primary key,
    crate_id      uuid             not null
        constraint fk_cargo_crate_meta__crate_id
            references cargo_crate
            on delete cascade,
    version       varchar(64)      not null,
    readme        text,
    license       varchar(255),
    license_file  varchar(255),
    documentation varchar(255),
    edition       varchar(10),
    rust_version  varchar(20),
    downloads     bigint default 0 not null,
    created_at    timestamp        not null
);

create unique index ux_cargo_crate_meta__crate_id_version
    on cargo_crate_meta (crate_id, version);

create index ix_cargo_crate_meta__crate_id
    on cargo_crate_meta (crate_id);

create table cargo_author
(
    id     uuid not null
        constraint pk_cargo_author
            primary key,
    author text not null
        constraint ux_cargo_author__author
            unique
);

create table cargo_keyword
(
    id      uuid not null
        constraint pk_cargo_keyword
            primary key,
    keyword text not null
        constraint ux_cargo_keyword__keyword
            unique
);

create table cargo_category
(
    id       uuid not null
        constraint pk_cargo_category
            primary key,
    category text not null
        constraint ux_cargo_category__category
            unique
);

create table cargo_crate_author
(
    id        uuid not null
        constraint pk_cargo_crate_author
            primary key,
    crate_id  uuid not null
        constraint fk_cargo_crate_author__crate_id
            references cargo_crate
            on delete cascade,
    author_id uuid not null
        constraint fk_cargo_crate_author__author_id
            references cargo_author
            on delete cascade
);

create unique index ux_cargo_crate_author__crate_id_author_id
    on cargo_crate_author (crate_id, author_id);


create table cargo_crate_keyword
(
    id         uuid not null
        constraint pk_cargo_crate_keyword
            primary key,
    crate_id   uuid not null
        constraint fk_cargo_crate_keyword__crate_id
            references cargo_crate
            on delete cascade,
    keyword_id uuid not null
        constraint fk_cargo_crate_keyword__keyword_id
            references cargo_keyword
            on delete cascade
);

create unique index ux_cargo_crate_keyword__crate_id_keyword_id
    on cargo_crate_keyword (crate_id, keyword_id);


create table cargo_crate_category
(
    id          uuid not null
        constraint pk_cargo_crate_category
            primary key,
    crate_id    uuid not null
        constraint fk_cargo_crate_category__crate_id
            references cargo_crate
            on delete cascade,
    category_id uuid not null
        constraint fk_cargo_crate_category__category_id
            references cargo_category
            on delete cascade
);

create unique index ux_cargo_crate_category__crate_id_category_id
    on cargo_crate_category (crate_id, category_id);
