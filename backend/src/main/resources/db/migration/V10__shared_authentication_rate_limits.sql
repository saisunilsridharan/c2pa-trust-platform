create table authentication_rate_settings (
 id bigint primary key, revision bigint, window_seconds integer not null, per_address_limit integer not null, global_limit integer not null, trusted_proxy_cidrs varchar(8000)
);
insert into authentication_rate_settings(id,revision,window_seconds,per_address_limit,global_limit,trusted_proxy_cidrs) values(1,0,60,30,1000,'');
create table authentication_rate_bucket (
 id varchar(64) primary key, started_at timestamp(6) with time zone, attempts integer not null
);
insert into authentication_rate_bucket(id,attempts) values('GLOBAL',0);
create index authentication_rate_bucket_started on authentication_rate_bucket(started_at);
