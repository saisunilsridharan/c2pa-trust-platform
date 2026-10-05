create table certificate_renewal_plan (
 id varchar(255) primary key, revision bigint, workspace_id bigint not null, provider_version varchar(255) not null,
 current_choice_id varchar(255) not null, current_choice_revision bigint, subject_configuration varchar(2000) not null,
 renew_before_hours integer not null, enabled boolean not null, state varchar(255), attempts integer not null,
 next_check_at timestamp(6) with time zone, lease_token varchar(255), lease_until timestamp(6) with time zone,
 created_at timestamp(6) with time zone, last_renewed_at timestamp(6) with time zone, last_identity_id varchar(255), error varchar(300)
);
create index certificate_renewal_plan_workspace on certificate_renewal_plan(workspace_id,created_at);
create index certificate_renewal_plan_due on certificate_renewal_plan(enabled,next_check_at);
