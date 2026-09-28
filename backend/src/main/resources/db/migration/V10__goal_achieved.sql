-- Reaching a goal is an event, not a comparison. Once you have saved what you
-- set out to save, spending it is what the saving was for, and the falling
-- balance must not start demanding a monthly contribution all over again.
alter table goals add column achieved_on date;

-- Anything already at or past its target has been reached; stamp it with today
-- rather than leaving it to flip back the first time it is spent from.
update goals g
   set achieved_on = current_date
 where g.achieved_on is null
   and g.target <= g.initial_amount
       + coalesce((select sum(c.amount) from goal_contributions c where c.goal_id = g.id), 0)
       - coalesce((select sum(t.amount) from transactions t where t.goal_id = g.id), 0);
