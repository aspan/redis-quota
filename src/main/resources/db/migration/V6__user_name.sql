-- A display name for each user, such as Anna Andersson, shown instead of the username where people read it; the
-- username stays the login and the id of the user's quota. Null when not set

alter table users add column name varchar(200);
