-- The sender ids each user may send SMS from, as SmsService.normalizeSenderId gives them: an alphanumeric id of at
-- most 11 characters, or a phone number of at most 15 digits after an optional +. Only admins change them
create table user_sender_ids (
    user_id   bigint      not null references users (id) on delete cascade,
    sender_id varchar(16) not null,
    primary key (user_id, sender_id)
);
