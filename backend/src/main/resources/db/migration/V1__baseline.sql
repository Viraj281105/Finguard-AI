-- V1: baseline schema.
-- Reproduces exactly what Hibernate (ddl-auto=update) generated for the original entities, so
-- databases created by the old setup can be adopted unchanged (spring.flyway.baseline-on-migrate).
-- Constraint names are made explicit; column names/types match the entity mappings.

create table users (
    id         varchar(255) not null,
    name       varchar(255) not null,
    email      varchar(255) not null,
    password   varchar(255) not null,
    created_at timestamp(6),
    constraint pk_users primary key (id),
    constraint uk_users_email unique (email)
);

create table transactions (
    id         varchar(255) not null,
    user_id    varchar(255) not null,
    title      varchar(255) not null,
    amount     numeric(38,2) not null,
    type       varchar(255) not null,
    category   varchar(255),
    note       varchar(255),
    created_at timestamp(6),
    constraint pk_transactions primary key (id),
    constraint fk_transactions_user foreign key (user_id) references users (id),
    constraint ck_transactions_type check (type in ('INCOME', 'EXPENSE'))
);

create table investments (
    id              varchar(255) not null,
    user_id         varchar(255) not null,
    name            varchar(255) not null,
    type            varchar(255) not null,
    amount_invested numeric(38,2) not null,
    current_value   numeric(38,2),
    notes           varchar(255),
    created_at      timestamp(6),
    constraint pk_investments primary key (id),
    constraint fk_investments_user foreign key (user_id) references users (id),
    constraint ck_investments_type check (type in ('STOCKS', 'MUTUAL_FUND', 'FD', 'CRYPTO', 'GOLD', 'OTHER'))
);

create table loans (
    id            varchar(255) not null,
    user_id       varchar(255) not null,
    name          varchar(255) not null,
    type          varchar(255) not null,
    principal     numeric(38,2) not null,
    emi_amount    numeric(38,2) not null,
    interest_rate numeric(38,2) not null,
    start_date    date,
    end_date      date,
    active        boolean,
    created_at    timestamp(6),
    constraint pk_loans primary key (id),
    constraint fk_loans_user foreign key (user_id) references users (id),
    constraint ck_loans_type check (type in ('HOME', 'CAR', 'PERSONAL', 'EDUCATION', 'OTHER'))
);

create table upload_jobs (
    id                varchar(255) not null,
    user_id           varchar(255) not null,
    original_filename varchar(255),
    bank_name         varchar(255),
    status            varchar(255) not null,
    rows_processed    integer,
    total_rows        integer,
    error_message     text,
    created_at        timestamp(6),
    completed_at      timestamp(6),
    constraint pk_upload_jobs primary key (id),
    constraint fk_upload_jobs_user foreign key (user_id) references users (id),
    constraint ck_upload_jobs_status check (status in ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);
