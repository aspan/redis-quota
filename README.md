# redis-quota

Usage quotas per user and period, backed by Redis and built with Spring Boot.

`QuotaManager` counts usage for a user over an hourly, daily, weekly, monthly or yearly period. It allows a request if the new total stays within the limit and rejects it otherwise. The check and the increment run in a single Lua script, so concurrent requests can never push usage past the limit.

## Requirements

- Java 27
- Docker (for the local Valkey and PostgreSQL instances and the integration tests)

## Usage

Each id has one quota: a limit for a single period. It is stored with `QuotaDefinitionRepository`, a Spring Data Redis repository that keeps it under `quota-definitions:<id>`. In this application quotas belong to users, so the ids are usernames and `UserAccountService` writes the definitions (see [User quotas](#user-quotas)); the example below shows the API that it and `QuotaManager` use:

```java
quotaDefinitionRepository.save(new QuotaDefinition("user-123", QuotaPeriod.MONTHLY, 1000));

var status = quotaManager.checkAndIncrement("user-123", 250);
status.allowed();   // true
status.used();      // 250
status.remaining(); // 750
status.resetAt();   // Instant: start of next month in the clock's time zone

quotaManager.getStatus("user-123");   // same status, without consuming quota
quotaManager.getUsage("user-123");    // 250
quotaManager.getLastUsed("user-123"); // Optional<Instant> of the last allowed request
```

A rejected request does not consume quota, and its status reports the usage and remaining quota as they actually are. In the status from `getStatus`, `allowed` means at least one unit is left.

The per-id methods throw `IllegalArgumentException` if no quota is stored for the id. Changing the stored limit takes effect on the next request, and usage already counted is kept. Changing the stored period starts counting in the new period from zero; usage counted in the old period stays in its own key until that expires.

### User quotas

Every quota belongs to a user of the admin UI. A user's quota is kept with the user in PostgreSQL (`quota_period` and `quota_limit` in the `users` table), and `UserAccountService` copies it to Redis as the `QuotaDefinition` under the username whenever the user is saved, and deletes that definition when the quota is removed or the user deleted, together with the usage counted under the username (`QuotaManager.deleteUsage`: the counters of every period and the last-used time), so a quota given again starts from zero, so `QuotaManager` reads user quotas from Redis. The copy is written inside the database transaction, so if Redis cannot be reached the change is rolled back and an error shown. At startup and then once a day, `UserQuotaSync` makes Redis match the users: it copies all user quotas again, so they come back after Redis has lost its data, deletes every definition that is not a user's quota, such as one saved with `QuotaDefinitionRepository` in code, and deletes stale usage with `QuotaManager.deleteStaleUsage`: the usage counters and last-used times of ids that are not users with a quota, and counters of earlier periods or of a period the user's quota no longer has. It keeps each user's counter for the current period and their last-used time, so it can run while requests are counted. If Redis is down, it logs the error, the application keeps running, and the next run tries again. The daily run is at 03:30 in the server's time zone, set by the `quota.sync.cron` property (a Spring cron expression, or `-` to sync only at startup), which `QuotaSyncProperties` binds and checks at startup. Every instance of the application runs it; running it twice does no harm.

### Usage history

The final usage of each user's finished periods is kept in PostgreSQL, in the `quota_usage_history` table: the period, when it started, the usage and the limit at the time. `QuotaHistoryService` reads the counters of each user's finished periods with `QuotaManager.getFinishedPeriodUsage` and records them. `UserQuotaSync` runs it five minutes past every hour, so a period shows up soon after it ends, and at the start of every sync, including the one at startup, before stale counters are deleted. The counter of a finished period stays in Redis for `quota.counter.retention` (default `7d`) after the period ends, so periods that ended while the application was down are recorded when it is back, as long as it was down for less than that; `QuotaCounterProperties` binds and checks it. Recording a period again updates its row. A period in which nothing was counted gets no row. Rows older than `quota.history.retention` (default `365d`, counted from the start of the period) are deleted by the daily sync, and a user's rows are deleted with the user.

### Explicit quotas

Callers that do not store definitions can pass the limit and period with each call instead:

```java
var config = new QuotaConfig(1000, QuotaPeriod.MONTHLY);

quotaManager.checkAndIncrement("user-123", config, 250);
quotaManager.getStatus("user-123", config);
```

These count usage in the same keys as stored definitions with the same period.

### Changing usage

```java
quotaManager.adjustUsage("user-123", -50); // 200: refund 50, never below 0
quotaManager.setUsage("user-123", 900);    // overwrite usage
quotaManager.resetQuota("user-123");       // clear usage for the current period
```

These act on the current period of the stored quota, and keys they write expire with the period like any other.

### How usage is stored

Usage is kept in one Redis key per id and period:

| Period | Key example (2026-10-14) | Resets at |
|---|---|---|
| `HOURLY` | `quota:user-123:hourly:2026101410+0200` | Start of the next hour |
| `DAILY` | `quota:user-123:daily:20261014` | Start of the next day |
| `WEEKLY` | `quota:user-123:weekly:20261012` | Start of next Monday (ISO weeks) |
| `MONTHLY` | `quota:user-123:monthly:202610` | Start of the 1st of next month |
| `YEARLY` | `quota:user-123:yearly:2026` | Start of January 1 next year |

The time of the last allowed request is stored per id, across all periods, under `quota:user-123:last-used` (epoch milliseconds, no expiry). It is written atomically with the usage, so rejected requests do not update it, and `resetQuota` leaves it unchanged. Since it never expires, the daily sync deletes it once its id is no longer a user with a quota (see [User quotas](#user-quotas)).

Reset times use the time zone of the injected `java.time.Clock` (the system default in production). Each key expires `quota.counter.retention` (default seven days) after its period resets, so the usage history can still record it. The hourly key includes the UTC offset, so the hour that repeats when clocks go back for daylight saving time gets its own quota.

### Failing open

If Redis cannot be reached, `checkAndIncrement` logs an error and allows the request with full remaining quota, `getUsage` returns 0, and `getLastUsed` returns empty. When the stored definition cannot be read, the request is also allowed, with `limit`, `used` and `remaining` all reported as 0 and `resetAt` set to the current time, since the period is unknown. A Redis outage never blocks traffic, but quotas are not enforced while it lasts, so watch for `Redis error in checkAndIncrement` in the logs.

`resetQuota`, `adjustUsage` and `setUsage` do not fail open: they throw the Redis exception, so callers know the change was not made.

### HTTP interceptor

Requests under `/api/**` log in as a user with HTTP Basic on every request, checked against the users in PostgreSQL, and require the `USER` role, which admins have too through a role hierarchy in which `ADMIN` implies `USER`; the API keeps no session. `QuotaInterceptor` then counts each request as one unit against the user's quota, read from Redis under the username (see [User quotas](#user-quotas)). It adds these response headers:

- `X-Quota-Limit`
- `X-Quota-Used`
- `X-Quota-Remaining`
- `X-Quota-Reset` (ISO-8601 instant)


| Response | When |
|---|---|
| `401 Unauthorized` | No login, a wrong password or a disabled user. The request is not counted. |
| `403 Forbidden` | The user lacks the `USER` role, with `"error": "Forbidden"` in the JSON body; the request is not counted. Or no quota is stored for the username, with `"error": "No Quota"`. |
| `429 Too Many Requests` | The quota is used up. The JSON body has the limit, usage, remaining quota and reset time. |

Allowed requests carry their `QuotaStatus` in the `quotaStatus` request attribute (`QuotaInterceptor.STATUS_ATTRIBUTE`) for the handler. If Redis cannot be reached, requests are allowed, as described in [Failing open](#failing-open). HTTP Basic checks the bcrypt password on every request, which costs some CPU time per request.

### Quota status endpoint

`GET /api/quota` shows the logged-in user their own quota. It is left out of the interceptor, so it does not use up quota and still answers when the quota is used up:

```bash
curl -u alice:<password> http://localhost:8080/api/quota
```

```json
{
  "id": "alice",
  "period": "DAILY",
  "limit": 500,
  "used": 120,
  "remaining": 380,
  "resetAt": "2026-10-15T22:00:00Z",
  "lastUsed": "2026-10-15T09:12:44.118Z"
}
```

`lastUsed` is `null` until the first allowed request. A user without a quota gets `403` with `"error": "No Quota"`. Unlike requests, which fail open, the endpoint answers `503` with `"error": "Quota Unavailable"` when the quota cannot be read from Redis, since a status it could not read would only mislead.

### Sending SMS

`SmsService` sends SMS for a user and charges them to the user's quota, read from Redis under the username like API requests: each part of the message sent to each recipient uses one unit, so a message of two parts to three recipients uses six. A text of only GSM 7-bit characters, which include the Swedish letters, fits 160 characters in one part and 153 in each part of a longer one, where the extension characters such as `€` and `{` take two; a text with any other character, such as an emoji, is sent as UCS-2, with 70 and 67 (`SmsParts`). A message can have up to 10 parts and 100 recipients, and a recipient must be a mobile number. Recipients are checked and normalized with Google's [libphonenumber](https://github.com/google/libphonenumber) (`com.googlecode.libphonenumber:libphonenumber`): a number must be valid and of the mobile type, or of the type that can be either mobile or fixed-line, as in the US, where the two cannot be told apart, and is sent in E.164 form, such as `+46701234567` for `+46 70-123 45 67`. A number without a country code, such as `070-123 45 67`, is read as a number in the region set by `quota.sms.default-region` (an ISO 3166 country code, default `SE`), which `SmsProperties` binds and checks at startup.

Each send has a sender id, which recipients see the message come from: a phone number of 6 to 15 digits with an optional `+`, written with or without spaces, dashes, dots and parentheses, which are removed, and not checked with libphonenumber, since a sender can be a fixed-line number or a short code, or an alphanumeric id such as a company name. An alphanumeric id has at most 11 characters, the most the SMS header holds, of the letters A–Z and a–z, digits, spaces and `. - _ &`, with at least one letter so that it cannot be taken for a phone number; spaces around it are removed (`SmsService.normalizeSenderId`). Whether a gateway accepts an alphanumeric sender id, and which, depends on the gateway and the recipient's country.

A user can send only from the sender ids an admin has approved for them, which are stored normalized in the `user_sender_ids` table. Only the admin-only **Users** view changes them, through `UserAccountService.createUser` and `updateUser`; the methods a user changes their own profile, settings or password with leave them alone. `SmsService` checks the sender id against the user's approved ones before charging anything, and sends nothing for one that is not approved.

The whole cost is charged with `checkAndIncrement` before anything is sent, so a send that does not fit in the quota sends nothing and charges nothing. The units of recipients that the gateway does not accept are refunded with `adjustUsage`. Like API requests, sending fails open when Redis cannot be reached (see [Failing open](#failing-open)).

The messages are handed to an `SmsSender` with the sender id, recipient and text. The only one so far, `LoggingSmsSender`, sends nothing and logs each message; replace it with an `SmsSender` for a real gateway, which throws `SmsSendException` for a message it does not accept.

## Running

```bash
./mvnw spring-boot:run       # starts Valkey and PostgreSQL from compose.yaml via Docker Compose
./mvnw spring-boot:test-run  # the same, with the test settings: the local admin account and opening the browser
```

Both use the same containers and volumes, so data stays between runs. Only the tests start Valkey and PostgreSQL in Testcontainers containers, from `biz.asplund.testcontainers`, a package the application does not scan.

Local development and the tests use [Valkey](https://valkey.io), an open source fork of Redis that speaks the same protocol, from the `valkey/valkey` image. The application itself works with either; `compose.yaml` labels the service so that Spring Boot connects to it as Redis. Its data is kept in the `valkey-data` volume with append-only persistence turned on, so quotas and usage survive removing the container; `docker compose down -v` deletes them.

The admin UI's users and roles are stored in PostgreSQL with Spring Data JPA. Flyway creates the schema from `src/main/resources/db/migration`, and Hibernate only validates that the entities match it. `compose.yaml` runs PostgreSQL 18 with the `quota` database, user and password, which are for local development only, and keeps its data in the `postgres-data` volume. Valkey listens on port 6379 and PostgreSQL on 5432 on `127.0.0.1`, so tools such as `valkey-cli` or `psql` can connect to them directly; they are not reachable from other machines. A deployment sets `spring.datasource.url`, `spring.datasource.username` and `spring.datasource.password`.

### Admin UI

The application includes a Vaadin UI at <http://localhost:8080/>. Every logged-in user lands on **Home**, which shows their own quota: its usage in the current period, read-only, and its usage history chart, or a note that they have no quota and so cannot use the API.

**Quotas** (`/quotas`) lists the stored quotas with their period, current usage, reset time and last-used time. Select a quota to see its period and limit and its usage history chart, as in the **Users** view, and to set, adjust (add or refund a signed amount) or reset its usage in the current period, or run **Check and increment** with an amount to make a request against it as a client would, which also updates the last-used time. Apart from usage, the view is read-only: quotas belong to users, so they are created, changed and removed on the user in the **Users** view. The view lists only users' quotas.

The **Users** view lists the users who can log in, with their name, email address, roles, quota and whether they are active; the filter matches the username, the name or the email address. A user's email address is optional and checked to be one; no two users can have the same address, whatever its case, which a unique index in the database backs up. Each user can have a name, such as Anna Andersson, which is shown instead of the username in the side menu and on Home; the username stays the login and the id of the user's quota. Select a user to change their name or email address, set a new password, change their roles, quota or approved sender ids, disable them so they can no longer log in, or delete them. A quota is a period and limit counted under the username; choose **No quota** to remove it. Under **SMS**, type a sender id and press Enter to approve it for the user; it is normalized, and refused if it is not a valid sender id (see [Sending SMS](#sending-sms)). For a user with a quota, the editor also shows its usage in the current period and the last-used time, with the same usage card as the quotas view: set, adjust or reset the usage, or run **Check and increment**. Below it, **Usage history** shows the usage of the last 30 finished periods as a bar chart, oldest on the left, with the limit at the time as a dashed line and the exact numbers in tooltips (see [Usage history](#usage-history)). Bars are green, orange when less than 20% of the limit was left, and red when it was reached. The chart is drawn by [Chart.js](https://www.chartjs.org/) through the [vaadin-chartjs](https://github.com/jumpmindinc/symmetric-vaadin-chartjs) add-on (`org.jumpmind.symmetric:vaadin-chartjs`); it draws on a canvas, so its colors do not follow the light or dark theme. In the **Quotas** view, its usage can be changed the same way. **New user** creates one with a password of at least 8 characters. **Roles** opens a dialog that lists the roles with the number of users holding each, adds roles (stored in upper case, without the `ROLE_` prefix) and deletes them, which also removes them from their users. To keep an admin able to log in, you cannot delete or disable your own account or remove the `ADMIN` role from it, and the built-in `ADMIN` and `USER` roles cannot be deleted; `UserAccountService` enforces these rules. Changes apply at the next login: sessions already logged in keep the roles they had.

The entities carry Jakarta Validation constraints: `QuotaDefinition` needs an id, a period and a limit of 0 or more, `UserAccount` a username of at most 100 characters and a complete quota (a period and a limit, or neither), and `Role` an upper-case name without `ROLE_`. The user editor and the roles dialog bind their fields with Vaadin's `BeanValidationBinder`, which checks these constraints as the fields change and shows the messages from `ValidationMessages.properties` (English) and `ValidationMessages_sv.properties` (Swedish) in the UI language. Hibernate checks the same constraints again before it writes a user or role. Rules that need the database, such as a username already taken, and the limits on your own account stay in `UserAccountService`.

**Send SMS** (`/sms`) requires the `USER` or `ADMIN` role, as the API does, a quota to send with, and at least one sender id approved by an admin. Vaadin's view access check does not apply the role hierarchy, so the view lists both roles. It shows their quota read-only, as on **Home**, and sends a message from the approved sender id chosen, preselected when there is only one, to the mobile numbers entered, separated by commas or new lines, as described in [Sending SMS](#sending-sms). While typing it shows the length of the message, its number of parts and the units sending it will use. After a send the usage shown is read again; recipients the gateway did not accept are left in the field, so they can be sent to again.

**My profile** (`/profile`) is open to every logged-in user, not only admins. There users change their own name, email address, language and appearance, which apply at once when saved, and their password, for which they enter their current one first.

The UI requires a login. **Home** and **My profile** are open to every logged-in user; **Send SMS** requires the `USER` or `ADMIN` role; **Quotas** and **Users** require the `ADMIN` role. Views are listed in the side menu, with the logged-in user at its bottom, and the button at the top right, after the language and appearance buttons, logs out. Requests under `/api/**` use HTTP Basic instead of the login form, and any active user with the `USER` or `ADMIN` role and a quota can make them.

The UI is available in English and Swedish. It starts in the browser's language, or English for other languages, and the language button (the current language's flag, opening a menu of the languages with their flags) at the top right of every page, including the login page, switches it for all open tabs of the session without reloading. Spring's `LocaleResolver` (a `CookieLocaleResolver` in `I18nConfiguration`) remembers the choice in a `locale` cookie for a year, so it also applies after logging out and in later sessions; Spring MVC uses the same resolver. The languages are set by the `quota.ui.i18n.locales` property (default `en,sv`); the first one is used for browsers that accept none of them. Texts are in `src/main/resources/vaadin-i18n/`: `translations.properties` holds English and is the fallback for missing keys, `translations_sv.properties` holds Swedish, and the empty `translations_en.properties` makes sure English is served even on a JVM whose default locale is Swedish. To add a language, add a `translations_<language>.properties` file and the language to `quota.ui.i18n.locales`. The flags beside the languages, in the menu and in the language fields of the user editor and the profile page, come from the [flag-icons](https://github.com/lipis/flag-icons) npm package: `LanguageFlag` copies its 4x3 SVGs to the static assets with `@NpmPackage`, so a page loads only the flags it shows, and maps a language to a country, such as en to gb and sv to se, or else uses the language code, which is the country code for many languages.

Next to it, the appearance button cycles through following the operating system (the default), the light color scheme and the dark color scheme; its icon shows the current one. The choice is remembered in a `color-scheme` cookie for a year and is already set in the first page the browser loads, so a dark choice does not flash light while the app starts.

While a user is logged in, both choices are also saved with the user, in the `color_scheme` and `locale` columns of the `users` table, and the user's settings take precedence over the cookies on every page load, so they follow the user to any browser. Before login, and for a user whose setting is not set, the cookies and the browser's language decide as described above. Admins can also set a user's **Language** and **Appearance** in the user editor, where **Not set** leaves them to the browser. Saving your own account applies its settings at once, in every open tab; Not set then keeps what the browser remembers. Another user's settings apply from their next page load.

Logins are checked against the `users`, `roles` and `user_roles` tables by `DatabaseUserDetailsService`. Users log in with their username or their email address, whatever its case, in the login form and with HTTP Basic alike, and are logged in under their username either way, which is what quotas and settings are kept under. So that no login can mean two users, a username cannot be another user's email address, nor an email address another user's username. A role is stored without the `ROLE_` prefix, so the `ADMIN` role, which the first migration creates, grants `ROLE_ADMIN`. The third migration adds a `USER` role for users who are not admins, such as API clients; the API requires it, and like `ADMIN` it cannot be deleted. Passwords are encoded with Spring Security's `DelegatingPasswordEncoder`, which uses bcrypt and stores the encoding as a `{bcrypt}` prefix. A disabled user cannot log in.

The application itself defines no accounts. At startup, `AdminAccountInitializer` stores the user set by the `spring.security.user.name`, `spring.security.user.password` and `spring.security.user.roles` properties in the database, if no user with that name exists yet. It does nothing while the password is not set, and never changes a user that is already stored. For local development, `./mvnw spring-boot:test-run` sets the name `admin` and the `ADMIN` role in `src/test/resources/config/application.properties`, and reads the password from `config/local.properties`, which Git ignores:

```properties
spring.security.user.password=<your password>
```

The admin is stored in the PostgreSQL volume of `compose.yaml`, so once a test-run has created it, `./mvnw spring-boot:run` accepts the same login. Changing the password in `config/local.properties` later does not change the stored user; change it in the **Users** view, or delete the volume with `docker compose down -v` to start over.

A deployment can create its first admin the same way, with these properties set outside the repository, and then remove the password once the user is stored.

## Building and testing

```bash
./mvnw clean verify
```

All tests are integration tests (`*IT`) and run with the Maven Failsafe plugin during `verify`. Each one starts the full Spring application context.

- `QuotaManagerIT` runs against a real Valkey server in Testcontainers with a fixed clock (Wednesday 2026-10-14, Europe/Stockholm), so reset times, key names and expiry times are checked against exact values. It also checks which keys `deleteStaleUsage` deletes and keeps, reading the usage of every finished period whose counter is kept, across the repeated hour when clocks go back and leaving out periods that ended longer ago than `quota.counter.retention`, binding that property, and that `deleteUsage` deletes only the keys of its own id, not those of an id that starts with it or one containing SCAN wildcards.
- `QuotaManagerFailOpenIT` points the application at a Redis port with nothing listening on it, and checks that quotas fail open. It logs the expected connection errors.
- `QuotaViewIT` tests the admin UI with Vaadin browserless tests: the views run on the server against a real Valkey server, and Spring Security's `@WithMockUser` sets the logged-in user. It covers access control, the menu, listing and filtering quotas, showing the period and limit read-only without buttons to create, save or delete quotas, showing the usage history chart or that none is recorded, setting, adjusting and resetting usage, and check and increment.
- `LanguagePreferenceIT` checks how the language is resolved: the best translated match for the browser's `Accept-Language`, English as fallback, and a stored choice in the `locale` cookie taking precedence.
- `ColorSchemeIT` checks the appearance toggle: the system default, cycling through light and dark and back to system, keeping the choice across views, the translated label, reading the cookie, and setting the scheme in the first page.
- `I18nPropertiesIT` checks how `quota.ui.i18n.locales` binds: the defaults, a custom order and fallback, limiting the languages, and rejecting an empty list.
- `I18nIT` checks that the UI uses English by default, that the language menu switches the open views and the login form to Swedish and back, that both translation files have the same keys, that every configured locale has a translation file, and that English is served when the JVM's default locale is Swedish, that the validation messages exist in both languages, and that a validation message is shown in Swedish.
- `QuotaInterceptorIT` sends API requests with HTTP Basic to a test endpoint: counting them against the user's quota with the quota headers until a `429`, refusing a user without a quota or without the `USER` role with a `403`, letting an admin in through the role hierarchy, and not counting requests that fail to log in.
- `QuotaStatusControllerIT` checks `GET /api/quota`: showing the user's own period, limit, usage, reset and last-used time without using up quota, a used-up quota, no last use before the first request, refusing a user without a quota, and requiring a login.
- `SecurityConfigurationIT` checks the HTTP security rules with MockMvc: the login redirect, form login with the user stored in the database, logging in with the email address in the form and with HTTP Basic under the username, rejecting a disabled user, and that `/api/**` requires HTTP Basic and the `USER` role and keeps no session.
- `UserAccountServiceIT` checks managing users and roles: creating users with encoded passwords and roles, rejecting a missing or taken username, a short password and unknown roles, changing roles, the enabled flag and the password, copying quotas to Redis under the username and removing them with their usage, removing a definition under the username of a user without a quota, syncing Redis to the users' quotas and deleting stale usage while keeping the users' usage, Hibernate rejecting an incomplete quota and a lower-case role name on save, keeping your own account an enabled admin, storing sender ids normalized, keeping them when the user changes their own account and rejecting invalid ones, the role name rules, deleting a role from its users, the `ADMIN` and `USER` roles from the migrations, and keeping the built-in roles.
- `SmsServiceIT` checks sending SMS with the gateway mocked: counting parts in the GSM alphabet and UCS-2, normalizing mobile numbers, rejecting fixed-line numbers, checking the default region, normalizing sender ids, sending from a phone number, charging each part to each recipient, sending nothing from a sender id not approved for the user, when the quota has no room or the user no quota, refunding recipients the gateway rejects, and rejecting invalid input without charging.
- `SendSmsViewIT` checks the send SMS view: that it requires a login and the `USER` or `ADMIN` role, sending and showing the new usage, showing the cost and parts while typing, offering only the user's approved sender ids, rejecting a missing sender, numbers that are not mobile numbers and an empty message, sending nothing when the quota has no room, and the notes for a user without a quota or without approved sender ids.
- `HomeViewIT` checks the home view: that it requires a login, greeting the user by name, showing a user's own quota read-only with its history, and the note for a user without a quota.
- `ProfileViewIT` checks the profile page as a user with only the `USER` role: that it opens and that the menu holds only Home, Send SMS and My profile, saving and applying the name, language and appearance, checking the email address for its format and against other users', and changing the password, with a wrong current password, passwords that differ and a short one rejected.
- `UserSettingsIT` checks a stored user's language and appearance: saving the toolbar choices to the user, preferring the user's settings over the cookies, using the cookies when nothing is set or before login, editing and clearing the settings in the user editor, and applying your own settings at once when saved.
- `UserViewIT` tests the users view with Vaadin browserless tests: access control, listing users with their roles and status, creating a user and showing why one is invalid, setting and filtering by the name, editing roles and disabling a user, setting a user's quota, approving sender ids, showing and changing the usage of a user's quota, showing the usage history as a bar chart and telling when none is recorded, telling when the quota is missing in Redis, showing a user's quota read-only in the quotas view, the limits on your own account, deleting a user, and adding and deleting roles in the roles dialog.
- `QuotaSyncPropertiesIT` checks how `quota.sync.cron` binds: the default, a custom schedule, `-` turning the daily run off, and rejecting an invalid expression.
- `UserQuotaSyncIT` checks that the sync is scheduled daily at 03:30 and the history hourly, that running it removes a definition of no user, and that it records a finished period before deleting its counter.
- `QuotaHistoryServiceIT` checks the usage history: recording a user's finished period, recording every period that ended while nothing was recorded, updating a period when recorded again, recording nothing without usage or a quota, deleting periods past the retention and with the user, and binding `quota.history.retention`.
- `DatabaseUserDetailsServiceIT` checks the users and roles in a PostgreSQL server in Testcontainers: storing the configured admin with a bcrypt password at startup and leaving it alone on later starts, loading a user's roles, loading a disabled user, and rejecting an unknown user.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
