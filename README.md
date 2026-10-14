# redis-quota

Usage quotas per user and period, backed by Redis and built with Spring Boot.

`QuotaManager` counts usage for a user over a daily, weekly, monthly or yearly period. It allows a request if the new total stays within the limit and rejects it otherwise. The check and the increment run in a single Lua script, so concurrent requests can never push usage past the limit.

## Requirements

- Java 27
- Docker (for the local Redis instance and the integration tests)

## Usage

```java
var config = new QuotaConfig(1000, QuotaPeriod.MONTHLY);

var status = quotaManager.checkAndIncrement("user-123", config, 250);
status.isAllowed();   // true
status.getUsed();     // 250
status.getRemaining(); // 750
status.getResetAt();  // Instant: start of next month in the clock's time zone

quotaManager.getUsage("user-123", QuotaPeriod.MONTHLY); // 250
```

A rejected request does not consume quota, and its status reports the usage and remaining quota as they actually are.

### How usage is stored

Each user and period gets its own Redis key:

| Period | Key example (2026-10-14) | Resets at |
|---|---|---|
| `DAILY` | `quota:user-123:daily:20261014` | Start of the next day |
| `WEEKLY` | `quota:user-123:weekly:20261012` | Start of next Monday (ISO weeks) |
| `MONTHLY` | `quota:user-123:monthly:202610` | Start of the 1st of next month |
| `YEARLY` | `quota:user-123:yearly:2026` | Start of January 1 next year |

Reset times use the time zone of the injected `java.time.Clock` (the system default in production). Each key expires one hour after its period resets.

### Failing open

If Redis cannot be reached, `checkAndIncrement` logs an error and allows the request with full remaining quota, and `getUsage` returns 0. A Redis outage never blocks traffic, but quotas are not enforced while it lasts, so watch for `Redis error in checkAndIncrement` in the logs.

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

- `QuotaManagerTests` runs against a real Redis in Testcontainers with a fixed clock (Wednesday 2026-10-14, Europe/Stockholm), so reset times, key names and expiry times are checked against exact values.
- `QuotaManagerFailOpenTests` is a plain unit test with a mocked `RedisTemplate`. It covers the behaviour when Redis is down.

## License

Licensed under the [Apache License, Version 2.0](LICENSE).
