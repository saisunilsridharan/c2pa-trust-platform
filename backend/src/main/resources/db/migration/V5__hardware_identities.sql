create table hardware_identity (
 id varchar(255) primary key,
 workspace_id bigint not null,
 configuration varchar(16000) not null,
 certificate_path varchar(2000) not null,
 fingerprint varchar(64) not null,
 created_at timestamp(6) with time zone,
 tested_at timestamp(6) with time zone
);
create index hardware_identity_workspace on hardware_identity(workspace_id,created_at);
