create table public_trust_settings (id bigint primary key, revision bigint, draft_version varchar(255), active_version varchar(255));
create table public_trust_version (id varchar(255) primary key, workspace_id bigint not null, configuration varchar(16000) not null, signer_list varchar(1000000), tsa_list varchar(1000000), fetched_at timestamp(6) with time zone, tested_at timestamp(6) with time zone, created_at timestamp(6) with time zone);
create index public_trust_version_workspace on public_trust_version(workspace_id,created_at);
