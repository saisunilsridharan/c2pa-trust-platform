create table remote_worker (id varchar(255) primary key, revision bigint, workspace_id bigint not null, label varchar(120) not null, token_hash varchar(64) not null, expires_at timestamp(6) with time zone not null, enabled boolean not null default false, created_at timestamp(6) with time zone, last_seen_at timestamp(6) with time zone, tested_at timestamp(6) with time zone, probe_job_id varchar(255), namespace_available boolean not null default false);
create index remote_worker_workspace on remote_worker(workspace_id,created_at);
create table remote_worker_settings (id bigint primary key, revision bigint, remote_queued_signing boolean not null default false, agent_enabled boolean not null default false, agent_configuration varchar(16000), agent_tested_at timestamp(6) with time zone);
alter table signing_job add column remote_queued boolean not null default false;
alter table signing_job add column remote_worker_id varchar(255);
alter table signing_job add column remote_target varchar(255);
alter table signing_job add column remote_callbacks integer not null default 0;
create index signing_job_remote_queue on signing_job(workspace_id,state,remote_queued,created_at);
