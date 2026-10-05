alter table processing_settings add column max_memory_mb integer not null default 1024;
alter table processing_settings add column max_cpu_seconds integer not null default 45;
alter table processing_settings add column sandbox_mode varchar(32) not null default 'LIMITED';
alter table signing_job add column max_memory_mb integer not null default 1024;
alter table signing_job add column max_cpu_seconds integer not null default 45;
alter table signing_job add column sandbox_mode varchar(32) not null default 'LIMITED';
