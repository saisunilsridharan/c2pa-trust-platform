alter table encrypted_credential add column kind varchar(32) default 'INTEGRATION' not null;
create table account_factor (
 id bigint primary key,
 active_credential varchar(255), pending_credential varchar(255),
 pending_expires_at timestamp(6) with time zone,
 enabled boolean not null, last_step bigint not null,
 recovery_hashes varchar(4000), account_recovery_hash varchar(255)
);
