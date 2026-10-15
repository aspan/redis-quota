-- Each user may have a quota: a limit for a single period. UserAccountService copies it to Redis, where
-- QuotaManager reads it as the QuotaDefinition stored under the username

alter table users
    add column quota_period varchar(10),
    add column quota_limit  integer,
    -- Either both are set or neither
    add constraint users_quota_complete check ((quota_period is null) = (quota_limit is null)),
    add constraint users_quota_period check (quota_period in ('HOURLY', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY')),
    add constraint users_quota_limit check (quota_limit >= 0);
