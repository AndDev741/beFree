-- Single user today, multi-user later. Tagging every row with its owner now is
-- the expensive half of that migration; filtering by owner is a WHERE clause
-- that can be added when a second user exists.
--
-- The assistant thread is also unified here: it used to be keyed by the
-- WhatsApp phone number, so the app and WhatsApp would have been separate
-- conversations. Keyed by owner, "apaga o ultimo" works from either channel.

alter table transactions        add column owner varchar(64);
alter table categories          add column owner varchar(64);
alter table budgets             add column owner varchar(64);
alter table goals               add column owner varchar(64);
alter table goal_contributions  add column owner varchar(64);

update transactions       set owner = 'andre' where owner is null;
update categories         set owner = 'andre' where owner is null;
update budgets            set owner = 'andre' where owner is null;
update goals              set owner = 'andre' where owner is null;
update goal_contributions set owner = 'andre' where owner is null;

alter table transactions        alter column owner set not null;
alter table categories          alter column owner set not null;
alter table budgets             alter column owner set not null;
alter table goals               alter column owner set not null;
alter table goal_contributions  alter column owner set not null;

create index idx_transactions_owner on transactions (owner);

-- Re-key the existing conversation from the phone number to the owner.
delete from chat_memory where memory_id = 'andre';
update chat_memory set memory_id = 'andre';

-- Accounts. Seeded on first start from BEFREE_PASSWORD, never from a commit.
create sequence app_users_SEQ start with 1 increment by 50;

create table app_users (
    id       bigint       not null primary key,
    username varchar(64)  not null unique,
    password varchar(255) not null,
    role     varchar(64)  not null
);
