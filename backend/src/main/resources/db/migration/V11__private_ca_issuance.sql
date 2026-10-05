create table private_ca_settings (
 id bigint primary key, revision bigint, draft_version varchar(255), active_version varchar(255)
);
create table private_ca_version (
 id varchar(255) primary key, workspace_id bigint not null, configuration varchar(80000) not null, created_at timestamp(6) with time zone, tested_at timestamp(6) with time zone
);
create index private_ca_version_workspace on private_ca_version(workspace_id,created_at);
