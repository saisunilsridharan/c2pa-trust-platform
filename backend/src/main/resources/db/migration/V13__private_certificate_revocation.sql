create table revocation_settings (id bigint primary key, revision bigint, draft_version varchar(255), active_version varchar(255));
create table revocation_version (id varchar(255) primary key, workspace_id bigint not null, configuration varchar(500000) not null, created_at timestamp(6) with time zone, tested_at timestamp(6) with time zone);
create index revocation_version_workspace on revocation_version(workspace_id,created_at);
