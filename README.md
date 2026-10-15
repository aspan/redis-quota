# redis-quota

Usage quotas per user and period, backed by Redis and built with Spring Boot.

`QuotaManager` counts usage for a user over an hourly, daily, weekly, monthly or yearly period. It allows a request if the new total stays within the limit and rejects it otherwise. The check and the increment run in a single Lua script, so concurrent requests can never push usage past the limit.

## Requirements

- Java 27
- Docker (for the local Valkey instance and the integration tests)

## Usage

Each id (a user, an API key, ...) has one quota: a limit for a single period. It is stored with `QuotaDefinitionRepository`, a Spring Data Redis repository that keeps it under `quota-definitions:<id>`:

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

### Explicit quotas

Callers that do not store definitions, such as the HTTP interceptor, can pass the limit and period with each call instead:

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

The time of the last allowed request is stored per id, across all periods, under `quota:user-123:last-used` (epoch milliseconds, no expiry). It is written atomically with the usage, so rejected requests do not update it, and `resetQuota` leaves it unchanged.

Reset times use the time zone of the injected `java.time.Clock` (the system default in production). Each key expires one hour after its period resets. The hourly key includes the UTC offset, so the hour that repeats when clocks go back for daylight saving time gets its own quota.

### Failing open

If Redis cannot be reached, `checkAndIncrement` logs an error and allows the request with full remaining quota, `getUsage` returns 0, and `getLastUsed` returns empty. When the stored definition cannot be read, the request is also allowed, with `limit`, `used` and `remaining` all reported as 0 and `resetAt` set to the current time, since the period is unknown. A Redis outage never blocks traffic, but quotas are not enforced while it lasts, so watch for `Redis error in checkAndIncrement` in the logs.

`resetQuota`, `adjustUsage` and `setUsage` do not fail open: they throw the Redis exception, so callers know the change was not made.

### HTTP interceptor

`QuotaInterceptor` applies a quota to requests under `/api/**` and adds these response headers:

- `X-Quota-Limit`
- `X-Quota-Used`
- `X-Quota-Remaining`
- `X-Quota-Reset` (ISO-8601 instant)

When the quota is exhausted it responds with `429 Too Many Requests` and a JSON body describing the limit, usage and reset time.

> **Note:** The interceptor bean is currently created without a `QuotaConfig` or `KeyExtractor`, so it needs both before `/api/**` requests can work.

## Running

```bash
./mvnw spring-boot:run       # starts Valkey from compose.yaml via Docker Compose
./mvnw spring-boot:test-run  # starts Valkey in a Testcontainers container instead
```

Local development and the tests use [Valkey](https://valkey.io), an open source fork of Redis that speaks the same protocol, from the `valkey/valkey` image. The application itself works with either; `compose.yaml` labels the service so that Spring Boot connects to it as Redis.

### Admin UI

The application includes a Vaadin admin UI at <http://localhost:8080/>. It lists the stored quotas with their period, current usage, reset time and last-used time. Select a quota to change its period or limit, delete it, set, adjust (add or refund a signed amount) or reset its usage in the current period, or run **Check and increment** with an amount to make a request against it as a client would, which also updates the last-used time. **New quota** creates a definition.

The UI requires a login with the `ADMIN` role. Views are listed in the side menu, and the user menu at the bottom logs out. Requests under `/api/**` are not behind the login, since API clients are identified by the quota interceptor.

The UI is available in English and Swedish. It starts in the browser's language, or English for other languages, and the language button (a globe with the language code) at the top right of every page, including the login page, switches it for all open tabs of the session without reloading. Spring's `LocaleResolver` (a `CookieLocaleResolver` in `I18nConfiguration`) remembers the choice in a `locale` cookie for a year, so it also applies after logging out and in later sessions; Spring MVC uses the same resolver. The languages are set by the `quota.ui.i18n.locales` property (default `en,sv`); the first one is used for browsers that accept none of them. Texts are in `src/main/resources/vaadin-i18n/`: `translations.properties` holds English and is the fallback for missing keys, `translations_sv.properties` holds Swedish, and the empty `translations_en.properties` makes sure English is served even on a JVM whose default locale is Swedish. To add a language, add a `translations_<language>.properties` file and the language to `quota.ui.i18n.locales`.

Next to it, the appearance button opens a menu that switches between the light and dark color schemes, or follows the operating system (the default). The choice is remembered in a `color-scheme` cookie for a year and is already set in the first page the browser loads, so a dark choice does not flash light while the app starts.

The application itself defines no accounts. For local development, `./mvnw spring-boot:test-run` adds an `admin` user with the `ADMIN` role from `src/test/resources/config/application.properties`. Its password is read from `config/local.properties`, which Git ignores:

```properties
spring.security.user.password=<your password>
```

Without that file, Spring Boot generates a password at each start and logs it as `Using generated security password`. A deployment has to provide its own users, for example with `spring.security.user.*` properties or a `UserDetailsService` bean.

## Building and testing

```bash
./mvnw clean verify
```

All tests are integration tests (`*IT`) and run with the Maven Failsafe plugin during `verify`. Each one starts the full Spring application context.

- `QuotaManagerIT` runs against a real Valkey server in Testcontainers with a fixed clock (Wednesday 2026-10-14, Europe/Stockholm), so reset times, key names and expiry times are checked against exact values.
- `QuotaManagerFailOpenIT` points the application at a Redis port with nothing listening on it, and checks that quotas fail open. It logs the expected connection errors.
- `QuotaViewIT` tests the admin UI with Vaadin browserless tests: the views run on the server against a real Valkey server, and Spring Security's `@WithMockUser` sets the logged-in user. It covers access control, the menu, listing and filtering quotas, creating, validating, editing and deleting definitions, setting, adjusting and resetting usage, and check and increment.
- `LanguagePreferenceIT` checks how the language is resolved: the best translated match for the browser's `Accept-Language`, English as fallback, and a stored choice in the `locale` cookie taking precedence.
- `ColorSchemeIT` checks the appearance menu: the system default, switching the page to light or dark, keeping the choice across views, translated choices, reading the cookie, and setting the scheme in the first page.
- `I18nPropertiesIT` checks how `quota.ui.i18n.locales` binds: the defaults, a custom order and fallback, limiting the languages, and rejecting an empty list.
- `I18nIT` checks that the UI uses English by default, that the language menu switches the open views and the login form to Swedish and back, that both translation files have the same keys, that every configured locale has a translation file, and that English is served when the JVM's default locale is Swedish.
- `SecurityConfigurationIT` checks the HTTP security rules with MockMvc: the login redirect, form login, and that `/api/**` needs no login.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
