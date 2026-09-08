-- Conversation memory for the AI assistant (one JSON blob per chat) and the
-- exactly-once ledger of inbound chat messages (Meta redelivers on any non-2xx)

create table chat_memory (
    memory_id  varchar(64) primary key,
    messages   text not null,
    updated_at timestamp(6) with time zone not null
);

create table inbound_messages (
    external_id varchar(255) primary key,
    source      varchar(16)  not null,
    received_at timestamp(6) with time zone not null
);
