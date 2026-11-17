-- Money lent to someone, and what they have paid back.
--
-- What is still owed is worked out at read time from the repayments, the same
-- way a goal's balance is, so deleting a wrong repayment simply reopens the
-- loan. A repayment keeps its own currency: someone who borrowed euros may pay
-- back in reais, and only the person can say when the conversion adds up, so
-- closing a loan by hand is a separate, dated act with a note.
--
-- transaction_id points at the expense that paid the loan out, when there is
-- one in the ledger. Deleting that movement only drops the link.

create sequence loans_SEQ start with 1 increment by 50;
create sequence loan_repayments_SEQ start with 1 increment by 50;

create table loans (
    id             bigint        not null primary key,
    person         varchar(255)  not null,
    description    varchar(255),
    amount         numeric(12,2) not null,
    currency       varchar(3)    not null,
    lent_on        date          not null,
    transaction_id bigint        unique references transactions on delete set null,
    closed_on      date,
    closing_note   varchar(255),
    owner          varchar(64)   not null
);

create table loan_repayments (
    id          bigint        not null primary key,
    loan_id     bigint        not null references loans,
    amount      numeric(12,2) not null,
    currency    varchar(3)    not null,
    occurred_on date          not null,
    note        varchar(255),
    owner       varchar(64)   not null
);

create index idx_loan_repayments_loan on loan_repayments (loan_id);
