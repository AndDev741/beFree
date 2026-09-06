-- New input channel. The V1 inline check constraint got Postgres' default
-- name <table>_<column>_check; recreate it with the extended list.

alter table transactions drop constraint transactions_source_check;
alter table transactions add constraint transactions_source_check
    check (source in ('MANUAL', 'TELEGRAM', 'WHATSAPP', 'OPEN_BANKING'));
