-- Budgets (monthly allowance per category) and goals (money set aside).
-- Both store only what the user decides; progress is summed from
-- transactions and contributions at read time.

create sequence budgets_SEQ start with 1 increment by 50;
create sequence goals_SEQ start with 1 increment by 50;
create sequence goal_contributions_SEQ start with 1 increment by 50;

create table budgets (
    id           bigint        not null primary key,
    category_id  bigint        not null references categories,
    period       date          not null,
    limit_amount numeric(12,2) not null,
    currency     varchar(3)    not null,
    unique (category_id, period)
);

create table goals (
    id          bigint        not null primary key,
    name        varchar(255)  not null unique,
    description varchar(255),
    target      numeric(12,2) not null,
    target_date date,
    currency    varchar(3)    not null
);

create table goal_contributions (
    id          bigint        not null primary key,
    goal_id     bigint        not null references goals,
    amount      numeric(12,2) not null,
    occurred_on date          not null,
    note        varchar(255)
);

create index idx_budgets_period on budgets (period);
create index idx_goal_contributions_goal on goal_contributions (goal_id);
