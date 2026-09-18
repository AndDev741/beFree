-- Money saved for something gets spent on it eventually. That expense did not
-- come out of this month's income, it came out of the jar, so it points at the
-- goal it drained and stays out of the month's spending and its budgets.
alter table transactions add column goal_id bigint references goals;

create index idx_transactions_goal on transactions (goal_id);
