/*
 * Copyright 2026 Andreas Asplund
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package biz.asplund.redis.quota;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

@Component
public class QuotaManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuotaManager.class);

    private static final String QUOTA_PREFIX = "quota";
    private static final DateTimeFormatter HOUR_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHxx");

    // Adds a (possibly negative) delta to the usage, never going below zero
    private static final RedisScript<Long> ADJUST_USAGE_SCRIPT = RedisScript.of("""
            local usage = tonumber(redis.call('GET', KEYS[1]) or '0') + tonumber(ARGV[1])
            if usage < 0 then
                usage = 0
            end
            redis.call('SET', KEYS[1], usage, 'EX', tonumber(ARGV[2]))
            return usage
            """, Long.class);

    private final RedisTemplate<String, String> redisTemplate;
    private final QuotaDefinitionRepository quotaDefinitionRepository;
    private final Clock clock;

    public QuotaManager(RedisTemplate<String, String> redisTemplate,
            QuotaDefinitionRepository quotaDefinitionRepository, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.quotaDefinitionRepository = quotaDefinitionRepository;
        this.clock = clock;
    }

    public QuotaStatus checkAndIncrement(String id, QuotaPeriod period, int amount) {
        return withStoredLimit(id, period, config -> checkAndIncrement(id, config, amount));
    }

    public QuotaStatus checkAndIncrement(String id, QuotaConfig config, int amount) {
        var key = getPeriodKey(id, config.period());
        var lastUsedKey = getLastUsedKey(id);
        var ttl = getTTL(config.period());
        var resetAt = getResetTime(config.period());

        // Lua script for atomic operations
        var script = """
            local current = tonumber(redis.call('GET', KEYS[1]) or '0')
            local amount = tonumber(ARGV[1])
            local ttl = tonumber(ARGV[2])
            local limit = tonumber(ARGV[3])
            local new_usage = current + amount

            if new_usage <= limit then
                redis.call('INCRBY', KEYS[1], amount)
                redis.call('EXPIRE', KEYS[1], ttl)
                redis.call('SET', KEYS[2], ARGV[4])
                return {1, new_usage}
            end

            return {0, current}
            """;

        var redisScript = new DefaultRedisScript<List>();
        redisScript.setScriptText(script);
        redisScript.setResultType(List.class);

        try {
            var result = redisTemplate.execute(
                redisScript,
                List.of(key, lastUsedKey),
                String.valueOf(amount),
                String.valueOf(ttl),
                String.valueOf(config.limit()),
                String.valueOf(clock.millis())
            );

            if (result.size() >= 2) {
                var allowedFlag = ((Long) result.get(0)).intValue();
                var currentUsage = ((Long) result.get(1)).intValue();

                return new QuotaStatus(allowedFlag == 1, config.limit(), currentUsage,
                        Math.max(0, config.limit() - currentUsage), resetAt);
            }
        } catch (Exception e) {
            // Fail open in case of Redis issues
            LOGGER.error("Redis error in checkAndIncrement for id {}, failing open", id, e);
            return new QuotaStatus(true, config.limit(), 0, config.limit(), resetAt);
        }

        // Default return
        return new QuotaStatus(false, config.limit(), 0, 0, resetAt);
    }

    public QuotaStatus getStatus(String id, QuotaPeriod period) {
        return withStoredLimit(id, period, config -> getStatus(id, config));
    }

    // Current status without consuming quota; allowed means at least one unit is left
    public QuotaStatus getStatus(String id, QuotaConfig config) {
        var used = getUsage(id, config.period());
        return new QuotaStatus(used < config.limit(), config.limit(), used,
                Math.max(0, config.limit() - used), getResetTime(config.period()));
    }

    public int getUsage(String id, QuotaPeriod period) {
        try {
            var key = getPeriodKey(id, period);
            var value = redisTemplate.opsForValue().get(key);
            return value != null ? Integer.parseInt(value) : 0;
        } catch (Exception e) {
            LOGGER.error("Redis error in getUsage for id {}", id, e);
            return 0;
        }
    }

    public Optional<Instant> getLastUsed(String id) {
        try {
            var value = redisTemplate.opsForValue().get(getLastUsedKey(id));
            return Optional.ofNullable(value).map(millis -> Instant.ofEpochMilli(Long.parseLong(millis)));
        } catch (Exception e) {
            LOGGER.error("Redis error in getLastUsed for id {}", id, e);
            return Optional.empty();
        }
    }

    public void resetQuota(String id, QuotaPeriod period) {
        redisTemplate.delete(getPeriodKey(id, period));
    }

    public int adjustUsage(String id, QuotaPeriod period, int delta) {
        var usage = redisTemplate.execute(
            ADJUST_USAGE_SCRIPT,
            Collections.singletonList(getPeriodKey(id, period)),
            String.valueOf(delta),
            String.valueOf(getTTL(period))
        );
        return usage.intValue();
    }

    public void setUsage(String id, QuotaPeriod period, int usage) {
        if (usage < 0) {
            throw new IllegalArgumentException("usage must not be negative: " + usage);
        }
        redisTemplate.opsForValue().set(getPeriodKey(id, period), String.valueOf(usage),
            Duration.ofSeconds(getTTL(period)));
    }

    private QuotaStatus withStoredLimit(String id, QuotaPeriod period, Function<QuotaConfig, QuotaStatus> action) {
        int limit;
        try {
            limit = quotaDefinitionRepository.findById(id)
                .map(definition -> definition.limits().get(period))
                .orElseThrow(() -> new IllegalArgumentException(
                    "No " + period + " quota defined for id " + id));
        } catch (DataAccessException e) {
            // Fail open in case of Redis issues; the limit is unknown
            LOGGER.error("Redis error loading quota definition for id {}, failing open", id, e);
            return new QuotaStatus(true, 0, 0, 0, getResetTime(period));
        }
        return action.apply(new QuotaConfig(limit, period));
    }

    private String getPeriodKey(String id, QuotaPeriod period) {
        var now = ZonedDateTime.now(clock);
        var today = now.toLocalDate();

        var periodId = switch (period) {
            // Offset keeps the repeated hour apart when clocks go back for DST
            case HOURLY -> now.format(HOUR_FORMAT);
            case DAILY -> today.format(DateTimeFormatter.BASIC_ISO_DATE);
            // Week starts on Monday (ISO week)
            case WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .format(DateTimeFormatter.BASIC_ISO_DATE);
            case MONTHLY -> YearMonth.from(today).format(DateTimeFormatter.ofPattern("yyyyMM"));
            case YEARLY -> String.valueOf(today.getYear());
        };

        return String.format(Locale.ROOT, "%s:%s:%s:%s", QUOTA_PREFIX, id, period.name().toLowerCase(Locale.ROOT), periodId);
    }

    private String getLastUsedKey(String id) {
        return String.format(Locale.ROOT, "%s:%s:last-used", QUOTA_PREFIX, id);
    }

    private int getTTL(QuotaPeriod period) {
        var untilReset = Duration.between(clock.instant(), getResetTime(period));
        var ttlSeconds = untilReset.plusHours(1).toSeconds(); // Add 1 hour buffer
        return (int) Math.max(0, ttlSeconds);
    }

    private Instant getResetTime(QuotaPeriod period) {
        var now = ZonedDateTime.now(clock);
        var today = now.toLocalDate();

        return switch (period) {
            case HOURLY -> now.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant();
            case DAILY -> startOfDay(today.plusDays(1));
            case WEEKLY -> startOfDay(today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)));
            case MONTHLY -> startOfDay(today.with(TemporalAdjusters.firstDayOfNextMonth()));
            case YEARLY -> startOfDay(today.with(TemporalAdjusters.firstDayOfNextYear()));
        };
    }

    private Instant startOfDay(LocalDate date) {
        return date.atStartOfDay(clock.getZone()).toInstant();
    }
}
