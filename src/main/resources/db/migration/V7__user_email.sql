-- An email address for each user, optional, and unique whatever its case

alter table users add column email varchar(254);

create unique index users_email_unique on users (lower(email));
