-- A USER role for users who are not admins, such as API clients. Left alone if it was already added in the roles
-- dialog
insert into roles (name) values ('USER') on conflict (name) do nothing;
