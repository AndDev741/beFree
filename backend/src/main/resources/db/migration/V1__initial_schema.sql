-- Matches the Hibernate-generated DDL for the current entities
-- (Panache sequences use allocationSize 50, hence "increment by 50")

create sequence categories_SEQ start with 1 increment by 50;
create sequence transactions_SEQ start with 1 increment by 50;

create table categories (
    id   bigint       not null primary key,
    name varchar(255) not null unique
);

create table transactions (
    id          bigint        not null primary key,
    amount      numeric(12,2) not null,
    type        varchar(8)    not null check (type in ('EXPENSE', 'INCOME')),
    currency    varchar(3)    not null,
    occurredOn  date          not null,
    description varchar(255),
    category_id bigint references categories,
    source      varchar(16)   not null check (source in ('MANUAL', 'TELEGRAM', 'OPEN_BANKING')),
    external_id varchar(255),
    rawInput    text,
    createdAt   timestamp(6) with time zone not null,
    unique (source, external_id)
);

create index idx_transactions_occurred_on on transactions (occurredOn);
