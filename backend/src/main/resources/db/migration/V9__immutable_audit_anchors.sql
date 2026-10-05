create table audit_anchor_settings (
 id bigint primary key, revision bigint, draft_version varchar(255), active_version varchar(255), next_checkpoint_at timestamp(6) with time zone
);
create table audit_anchor_version (
 id varchar(255) primary key, workspace_id bigint not null, configuration varchar(80000) not null, created_at timestamp(6) with time zone, tested_at timestamp(6) with time zone
);
create index audit_anchor_version_workspace on audit_anchor_version(workspace_id,created_at);
create table audit_anchor_delivery (
 id varchar(255) primary key, revision bigint, workspace_id bigint not null, configuration varchar(80000) not null, checkpoint varchar(4000) not null, state varchar(255) not null, attempts integer not null, created_at timestamp(6) with time zone, next_attempt_at timestamp(6) with time zone, receipt varchar(10000)
);
create index audit_anchor_delivery_workspace on audit_anchor_delivery(workspace_id,created_at);
create index audit_anchor_delivery_pending on audit_anchor_delivery(state,next_attempt_at);
