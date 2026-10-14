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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Collections;
import java.util.List;

@Component
public class QuotaManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuotaManager.class);

    private static final String QUOTA_PREFIX = "quota";

    private final RedisTemplate<String, String> redisTemplate;
    private final Clock clock;

    public QuotaManager(RedisTemplate<String, String> redisTemplate, Clock clock) {
        this.redisTemplate = redisTemplate;
        this.clock = clock;
    }

    public QuotaStatus checkAndIncrement(String userId, QuotaConfig config, int amount) {
        var key = getPeriodKey(userId, config.period());
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
                Collections.singletonList(key),
                String.valueOf(amount),
                String.valueOf(ttl),
                String.valueOf(config.limit())
            );

            if (result.size() >= 2) {
                var allowedFlag = ((Long) result.get(0)).intValue();
                var currentUsage = ((Long) result.get(1)).intValue();

                return new QuotaStatus(allowedFlag == 1, config.limit(), currentUsage,
                        Math.max(0, config.limit() - currentUsage), resetAt);
            }
        } catch (Exception e) {
            // Fail open in case of Redis issues
            LOGGER.error("Redis error in checkAndIncrement for user {}, failing open", userId, e);
            return new QuotaStatus(true, config.limit(), 0, config.limit(), resetAt);
        }

        // Default return
        return new QuotaStatus(false, config.limit(), 0, 0, resetAt);
    }

    public int getUsage(String userId, QuotaPeriod period) {
        try {
            var key = getPeriodKey(userId, period);
            var value = redisTemplate.opsForValue().get(key);
            return value != null ? Integer.parseInt(value) : 0;
        } catch (Exception e) {
            LOGGER.error("Redis error in getUsage for user {}", userId, e);
            return 0;
        }
    }

    private String getPeriodKey(String userId, QuotaPeriod period) {
        var today = LocalDate.now(clock);

        var periodId = switch (period) {
            case DAILY -> today.format(DateTimeFormatter.BASIC_ISO_DATE);
            // Week starts on Monday (ISO week)
            case WEEKLY -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
                    .format(DateTimeFormatter.BASIC_ISO_DATE);
            case MONTHLY -> YearMonth.from(today).format(DateTimeFormatter.ofPattern("yyyyMM"));
            case YEARLY -> String.valueOf(today.getYear());
        };

        return String.format("%s:%s:%s:%s", QUOTA_PREFIX, userId, period.name().toLowerCase(), periodId);
    }

    private int getTTL(QuotaPeriod period) {
        var untilReset = Duration.between(clock.instant(), getResetTime(period));
        var ttlSeconds = untilReset.plusHours(1).toSeconds(); // Add 1 hour buffer
        return (int) Math.max(0, ttlSeconds);
    }

    private Instant getResetTime(QuotaPeriod period) {
        var today = LocalDate.now(clock);

        var resetDate = switch (period) {
            case DAILY -> today.plusDays(1);
            case WEEKLY -> today.with(TemporalAdjusters.next(DayOfWeek.MONDAY));
            case MONTHLY -> today.with(TemporalAdjusters.firstDayOfNextMonth());
            case YEARLY -> today.with(TemporalAdjusters.firstDayOfNextYear());
        };

        return resetDate.atStartOfDay(clock.getZone()).toInstant();
    }
}
