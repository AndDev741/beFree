-- The Telegram channel was removed before it ever recorded anything; the
-- source check no longer admits it.

alter table transactions drop constraint transactions_source_check;
alter table transactions add constraint transactions_source_check
    check (source in ('MANUAL', 'WHATSAPP', 'OPEN_BANKING'));
