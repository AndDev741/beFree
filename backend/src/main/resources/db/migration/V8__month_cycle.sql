-- The month does not have to start on the 1st. Money that arrives on the 25th
-- is next month's money, so the period it belongs to can start on the 25th too.
--
-- One default, plus an exception per month, because a pay date slips: the 25th
-- falls on a Saturday and that month alone begins on the 27th. Days are capped
-- at 28 by the API, since no February has a 29th every year.
create sequence app_settings_SEQ start with 1 increment by 50;

create table app_settings (
    id              bigint      not null primary key,
    owner           varchar(64) not null unique,
    month_start_day integer     not null default 1
);

create sequence month_starts_SEQ start with 1 increment by 50;

create table month_starts (
    id        bigint      not null primary key,
    owner     varchar(64) not null,
    period    date        not null,
    start_day integer     not null,
    unique (owner, period)
);
