# redis-quota

Usage quotas per user and period, backed by Redis and built with Spring Boot.

`QuotaManager` counts usage for a user over an hourly, daily, weekly, monthly or yearly period. It allows a request if the new total stays within the limit and rejects it otherwise. The check and the increment run in a single Lua script, so concurrent requests can never push usage past the limit.

## Requirements

- Java 27
- Docker (for the local Redis instance and the integration tests)

## Usage

```java
var config = new QuotaConfig(1000, QuotaPeriod.MONTHLY);

var status = quotaManager.checkAndIncrement("user-123", config, 250);
status.allowed();   // true
status.used();      // 250
status.remaining(); // 750
status.resetAt();   // Instant: start of next month in the clock's time zone

quotaManager.getUsage("user-123", QuotaPeriod.MONTHLY); // 250
quotaManager.getLastUsed("user-123");                  // Optional<Instant> of the last allowed request
```

A rejected request does not consume quota, and its status reports the usage and remaining quota as they actually are.

### Stored quota definitions

Instead of passing a `QuotaConfig`, limits can be stored per id with `QuotaDefinitionRepository` (a Spring Data Redis repository, kept under `quota-definitions:<id>`):

```java
quotaDefinitionRepository.save(new QuotaDefinition("user-123",
        Map.of(QuotaPeriod.HOURLY, 100, QuotaPeriod.MONTHLY, 10_000)));

var status = quotaManager.checkAndIncrement("user-123", QuotaPeriod.HOURLY, 1);
```

If no limit is stored for the id and period, this throws `IllegalArgumentException`. Changing a stored limit takes effect on the next request; usage already counted is kept.

### Changing usage

```java
quotaManager.adjustUsage("user-123", QuotaPeriod.MONTHLY, -50); // 200: refund 50, never below 0
quotaManager.setUsage("user-123", QuotaPeriod.MONTHLY, 900);    // overwrite usage
quotaManager.resetQuota("user-123", QuotaPeriod.MONTHLY);       // clear usage for the current period
```

These act on the current period only, and keys they write expire with the period like any other.

### How usage is stored

Each user and period gets its own Redis key:

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

If Redis cannot be reached, `checkAndIncrement` logs an error and allows the request with full remaining quota, `getUsage` returns 0, and `getLastUsed` returns empty. When the limit comes from a stored definition that cannot be read, the request is also allowed, with `limit`, `used` and `remaining` all reported as 0. A Redis outage never blocks traffic, but quotas are not enforced while it lasts, so watch for `Redis error in checkAndIncrement` in the logs.

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
./mvnw spring-boot:run       # starts Redis from compose.yaml via Docker Compose
./mvnw spring-boot:test-run  # starts Redis in a Testcontainers container instead
```

## Building and testing

```bash
./mvnw clean verify
```

All tests are integration tests (`*IT`) and run with the Maven Failsafe plugin during `verify`. Each one starts the full Spring application context.

- `QuotaManagerIT` runs against a real Redis in Testcontainers with a fixed clock (Wednesday 2026-10-14, Europe/Stockholm), so reset times, key names and expiry times are checked against exact values.
- `QuotaManagerFailOpenIT` points the application at a Redis port with nothing listening on it, and checks that quotas fail open. It logs the expected connection errors.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
