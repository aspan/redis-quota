-- Each user's appearance and language, picked in the toolbar or set in the user editor and applied whenever a page
-- loads while the user is logged in. Null means not set, so the browser's cookie or language decides

alter table users
    add column color_scheme varchar(10) check (color_scheme in ('system', 'light', 'dark')),
    -- A language tag such as sv or en
    add column locale       varchar(35);
