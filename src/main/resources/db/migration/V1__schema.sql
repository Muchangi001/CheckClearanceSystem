-- CTS schema. Flyway runs this inside the `cts` schema.
-- Money is always paise in bigint. Never numeric-as-float, never rupees.

create table bank (
    code          varchar(3)   primary key,          -- MICR bank code (digits 4-6 of the MICR code)
    name          varchar(100) not null,
    is_presenting boolean      not null default false -- the bank this system runs for
);

create table app_user (
    username      varchar(50)  primary key,
    password_hash varchar(100) not null,
    full_name     varchar(100) not null,
    roles         varchar(100) not null,              -- comma-separated: MAKER,CHECKER,OPS,DRAWEE
    enabled       boolean      not null default true
);

create table return_reason (
    code        varchar(2)   primary key,
    description varchar(200) not null
);

-- Payee accounts at the presenting bank. Credited when a cheque settles.
create table deposit_account (
    account_no    varchar(20)  primary key,
    holder_name   varchar(100) not null,
    balance_paise bigint       not null default 0
);

-- Drawer accounts at drawee banks. In production this is each drawee bank's
-- core banking system; here it backs the drawee simulator. Balance may go
-- negative only through deemed approval, which pays whatever the funds.
create table drawee_account (
    id            bigserial    primary key,
    micr_code     varchar(9)   not null,
    account_no    varchar(6)   not null,
    holder_name   varchar(100) not null,
    balance_paise bigint       not null,
    status        varchar(10)  not null default 'ACTIVE' check (status in ('ACTIVE', 'CLOSED')),
    unique (micr_code, account_no)
);

create table stop_payment (
    id         bigserial   primary key,
    account_id bigint      not null references drawee_account (id),
    serial     varchar(6)  not null,
    created_at timestamptz not null default now(),
    unique (account_id, serial)
);

-- Positive Pay: details the drawer registered before issuing the cheque.
create table positive_pay (
    id           bigserial    primary key,
    account_id   bigint       not null references drawee_account (id),
    serial       varchar(6)   not null,
    cheque_date  date         not null,
    payee_name   varchar(100) not null,
    amount_paise bigint       not null check (amount_paise > 0),
    created_at   timestamptz  not null default now(),
    unique (account_id, serial)
);

-- Public halves of the keys that signed presented items. The private key
-- never touches the database: it lives in memory (an HSM in production).
create table signing_key (
    key_id     varchar(36) primary key,
    algorithm  varchar(30) not null,
    public_key bytea       not null,
    created_at timestamptz not null default now()
);

create table settlement_batch (
    id           bigserial   primary key,
    settled_at   timestamptz not null default now(),
    item_count   int         not null,
    total_paise  bigint      not null,
    triggered_by varchar(50) not null
);

create table cheque (
    id                  bigserial    primary key,
    serial              varchar(6)   not null,
    micr_code           varchar(9)   not null,
    account_no          varchar(6)   not null,
    tx_code             varchar(2)   not null,
    amount_paise        bigint       not null check (amount_paise > 0),
    cheque_date         date         not null,
    payee_name          varchar(100) not null,
    depositor_account   varchar(20)  not null references deposit_account (account_no),
    status              varchar(20)  not null,
    status_reason       varchar(200),
    return_code         varchar(2)   references return_reason (code),
    deemed              boolean      not null default false,
    dedupe_key          varchar(64)  not null,
    data_hash           varchar(64),
    signature           bytea,
    signing_key_id      varchar(36)  references signing_key (key_id),
    captured_by         varchar(50)  not null,
    approved_by         varchar(50),
    decided_by          varchar(50),
    captured_at         timestamptz  not null default now(),
    presented_at        timestamptz,
    expires_at          timestamptz,
    decided_at          timestamptz,
    settlement_batch_id bigint       references settlement_batch (id),
    version             bigint       not null default 0
);

-- One instrument (MICR code + account + serial) can be live only once.
-- Rejected and returned items don't count: a return like 03 may be re-presented.
-- The service checks first; this index is the guarantee under concurrency.
create unique index cheque_live_instrument on cheque (dedupe_key)
    where status not in ('REJECTED', 'RETURNED');
create index cheque_status on cheque (status);

create table cheque_image (
    id           bigserial   primary key,
    cheque_id    bigint      not null references cheque (id),
    side         varchar(5)  not null check (side in ('FRONT', 'BACK')),
    content_type varchar(50) not null,
    sha256       varchar(64) not null,
    data         bytea       not null,
    unique (cheque_id, side)
);

-- Double-entry. Each posting is one row; every cheque's rows balance.
-- (cheque_id, entry_type) unique makes posting idempotent: a replayed
-- settlement cannot post twice.
create table ledger_entry (
    id                  bigserial   primary key,
    cheque_id           bigint      not null references cheque (id),
    settlement_batch_id bigint      references settlement_batch (id),
    entry_type          varchar(30) not null,
    account             varchar(60) not null,
    debit_paise         bigint      not null default 0 check (debit_paise >= 0),
    credit_paise        bigint      not null default 0 check (credit_paise >= 0),
    posted_at           timestamptz not null default now(),
    check ((debit_paise = 0) <> (credit_paise = 0)),
    unique (cheque_id, entry_type)
);

create table audit_event (
    id        bigserial    primary key,
    cheque_id bigint       references cheque (id),
    actor     varchar(50)  not null,
    action    varchar(40)  not null,
    detail    varchar(500),
    at        timestamptz  not null default now()
);
create index audit_event_cheque on audit_event (cheque_id);

-- The audit trail is append-only, enforced by the database rather than by
-- trusting every code path.
create function audit_event_immutable() returns trigger language plpgsql as $$
begin
    raise exception 'audit_event is append-only';
end $$;

create trigger audit_event_no_update before update or delete on audit_event
    for each row execute function audit_event_immutable();
