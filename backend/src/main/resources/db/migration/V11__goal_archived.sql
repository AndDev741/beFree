-- A goal you are done with, reached or not, still happened. Deleting it takes
-- its contributions with it and turns what it paid for into ordinary spending
-- of those months, so a finished goal is archived instead: it leaves the
-- active list and the monthly asks, and keeps everything it ever held.
alter table goals add column archived_on date;
