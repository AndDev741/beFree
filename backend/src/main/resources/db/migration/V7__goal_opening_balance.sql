-- Money that was already in the jar before the goal existed. It counts towards
-- the goal, but it is not a contribution: nothing left this month's income to
-- get there, so the monthly summary must not treat it as money set aside.
alter table goals add column initial_amount numeric(12,2) not null default 0;
