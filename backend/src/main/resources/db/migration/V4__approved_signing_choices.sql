create table signing_option (
 id varchar(255) primary key,
 revision bigint,
 workspace_id bigint not null,
 label varchar(120) not null,
 settings varchar(16000) not null,
 profile_revision bigint not null,
 fingerprint varchar(64) not null,
 certificate_path varchar(2000) not null,
 key_path varchar(2000) not null,
 development boolean not null,
 enabled boolean not null,
 created_at timestamp(6) with time zone
);
create index signing_option_workspace on signing_option(workspace_id,enabled,created_at);
