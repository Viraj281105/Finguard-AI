-- V2: Refresh tokens table for server-side revocation and session rotation,
-- plus lower-case email normalization and case-insensitive unique index.

-- 1. Refresh tokens table
create table refresh_tokens (
    id                     varchar(255) not null,
    user_id                varchar(255) not null,
    token_hash             varchar(255) not null,
    expires_at             timestamp(6) not null,
    created_at             timestamp(6) not null,
    revoked                boolean not null default false,
    replaced_by_token_hash varchar(255),
    constraint pk_refresh_tokens primary key (id),
    constraint fk_refresh_tokens_user foreign key (user_id) references users (id) on delete cascade,
    constraint uk_refresh_tokens_hash unique (token_hash)
);

create index idx_refresh_tokens_user on refresh_tokens (user_id);
create index idx_refresh_tokens_hash on refresh_tokens (token_hash);

-- 2. Guard against applying migration if case-variant duplicate emails exist
do $$
begin
    if exists (
        select lower(trim(email)), count(*)
        from users
        group by lower(trim(email))
        having count(*) > 1
    ) then
        raise exception 'Cannot apply migration V2: Duplicate emails found differing only by case in users table. Please resolve manually.';
    end if;
end $$;

-- 3. Normalize existing emails to lowercase trimmed values
update users set email = lower(trim(email));

-- 4. Enforce case-insensitive uniqueness at database level
create unique index uq_users_lower_email on users (lower(email));
