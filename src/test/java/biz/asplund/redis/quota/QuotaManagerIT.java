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

import biz.asplund.testcontainers.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class QuotaManagerIT {

    @Autowired
    private QuotaManager quotaManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private QuotaDefinitionRepository quotaDefinitionRepository;

    @Autowired
    private QuotaCounterProperties counterProperties;

    // Wednesday 2026-10-14 10:15:30 CEST; DST ends 2026-10-25, so later resets are in CET
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 14, 10, 15, 30, 0, ZoneId.of("Europe/Stockholm"));

    @TestBean
    private Clock clock;

    static Clock clock() {
        return Clock.fixed(NOW.toInstant(), NOW.getZone());
    }

    @Test
    void monthlyQuotaAllowsRequestsWithinLimitAndRejectsExcess() {
        var id = uniqueId();
        var config = new QuotaConfig(1000, QuotaPeriod.MONTHLY);

        var first = quotaManager.checkAndIncrement(id, config, 250);
        assertThat(first).as("first request (250)")
                .returns(true, QuotaStatus::allowed)
                .returns(250, QuotaStatus::used)
                .returns(750, QuotaStatus::remaining);

        var second = quotaManager.checkAndIncrement(id, config, 300);
        assertThat(second).as("second request (300)")
                .returns(true, QuotaStatus::allowed)
                .returns(550, QuotaStatus::used)
                .returns(450, QuotaStatus::remaining);

        var third = quotaManager.checkAndIncrement(id, config, 500);
        assertThat(third).as("third request (500) exceeds limit")
                .returns(false, QuotaStatus::allowed)
                .returns(550, QuotaStatus::used)
                .returns(450, QuotaStatus::remaining);

        assertThat(usage(id, QuotaPeriod.MONTHLY)).isEqualTo(550);
    }

    @Test
    void requestReachingExactLimitIsAllowed() {
        var id = uniqueId();
        var config = new QuotaConfig(100, QuotaPeriod.DAILY);

        var status = quotaManager.checkAndIncrement(id, config, 100);
        assertThat(status)
                .returns(true, QuotaStatus::allowed)
                .returns(100, QuotaStatus::used)
                .returns(0, QuotaStatus::remaining);

        var next = quotaManager.checkAndIncrement(id, config, 1);
        assertThat(next)
                .returns(false, QuotaStatus::allowed)
                .returns(0, QuotaStatus::remaining);
        assertThat(usage(id, QuotaPeriod.DAILY)).isEqualTo(100);
    }

    @Test
    void rejectedRequestDoesNotConsumeQuota() {
        var id = uniqueId();
        var config = new QuotaConfig(100, QuotaPeriod.DAILY);

        quotaManager.checkAndIncrement(id, config, 60);
        var rejected = quotaManager.checkAndIncrement(id, config, 50);
        assertThat(rejected).as("rejected request reports what is actually left")
                .returns(false, QuotaStatus::allowed)
                .returns(60, QuotaStatus::used)
                .returns(40, QuotaStatus::remaining);

        var status = quotaManager.checkAndIncrement(id, config, 40);
        assertThat(status).as("smaller request still fits after rejection")
                .returns(true, QuotaStatus::allowed)
                .returns(100, QuotaStatus::used)
                .returns(0, QuotaStatus::remaining);
    }

    @Test
    void requestLargerThanLimitIsRejectedForFreshUser() {
        var id = uniqueId();
        var config = new QuotaConfig(10, QuotaPeriod.DAILY);

        var status = quotaManager.checkAndIncrement(id, config, 11);

        assertThat(status)
                .returns(false, QuotaStatus::allowed)
                .returns(0, QuotaStatus::used)
                .returns(10, QuotaStatus::remaining);
        assertThat(usage(id, QuotaPeriod.DAILY)).isZero();
    }

    @Test
    void usersHaveIndependentQuotas() {
        var alice = uniqueId();
        var bob = uniqueId();
        var config = new QuotaConfig(100, QuotaPeriod.DAILY);

        quotaManager.checkAndIncrement(alice, config, 100);
        var bobStatus = quotaManager.checkAndIncrement(bob, config, 30);

        assertThat(bobStatus)
                .returns(true, QuotaStatus::allowed)
                .returns(30, QuotaStatus::used);
        assertSoftly(softly -> {
            softly.assertThat(usage(alice, QuotaPeriod.DAILY)).as("alice").isEqualTo(100);
            softly.assertThat(usage(bob, QuotaPeriod.DAILY)).as("bob").isEqualTo(30);
        });
    }

    @Test
    void periodsAreTrackedIndependently() {
        var id = uniqueId();

        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.HOURLY), 5);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 10);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.WEEKLY), 20);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.MONTHLY), 30);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.YEARLY), 40);

        assertSoftly(softly -> {
            softly.assertThat(usage(id, QuotaPeriod.HOURLY)).as("hourly").isEqualTo(5);
            softly.assertThat(usage(id, QuotaPeriod.DAILY)).as("daily").isEqualTo(10);
            softly.assertThat(usage(id, QuotaPeriod.WEEKLY)).as("weekly").isEqualTo(20);
            softly.assertThat(usage(id, QuotaPeriod.MONTHLY)).as("monthly").isEqualTo(30);
            softly.assertThat(usage(id, QuotaPeriod.YEARLY)).as("yearly").isEqualTo(40);
        });
    }

    @ParameterizedTest
    @CsvSource({
            "HOURLY,  2026-10-14T11:00+02:00",
            "DAILY,   2026-10-15T00:00+02:00",
            "WEEKLY,  2026-10-19T00:00+02:00",
            "MONTHLY, 2026-11-01T00:00+01:00",
            "YEARLY,  2027-01-01T00:00+01:00"
    })
    void resetTimeIsStartOfNextPeriodInClockZone(QuotaPeriod period, OffsetDateTime expectedReset) {
        var config = new QuotaConfig(1000, period);

        var status = quotaManager.checkAndIncrement(uniqueId(), config, 1);

        assertThat(status.resetAt()).isEqualTo(expectedReset.toInstant());
    }

    @ParameterizedTest
    @CsvSource({
            "HOURLY,  hourly:2026101410+0200, 2026-10-14T11:00+02:00",
            "DAILY,   daily:20261014,         2026-10-15T00:00+02:00",
            "WEEKLY,  weekly:20261012,        2026-10-19T00:00+02:00",
            "MONTHLY, monthly:202610,         2026-11-01T00:00+01:00",
            "YEARLY,  yearly:2026,            2027-01-01T00:00+01:00"
    })
    void usageIsStoredUnderPeriodKeyExpiringSevenDaysAfterReset(QuotaPeriod period, String keySuffix,
                                                                OffsetDateTime reset) {
        var id = uniqueId();
        var key = "quota:" + id + ":" + keySuffix;

        quotaManager.checkAndIncrement(id, new QuotaConfig(1000, period), 7);

        var expectedTtl = Duration.between(NOW.toInstant(), reset.toInstant()).plusDays(7).toSeconds();
        var ttl = redisTemplate.getExpire(key);
        assertSoftly(softly -> {
            softly.assertThat(redisTemplate.opsForValue().get(key)).as("stored usage").isEqualTo("7");
            softly.assertThat(ttl).as("TTL in seconds").isBetween(expectedTtl - 4, expectedTtl);
        });
    }

    @Test
    void hourlyQuotaKeepsRepeatedDstHourSeparate() {
        // Clocks go back at 03:00 CEST on 2026-10-25, so 02:30 local time happens twice
        var zone = ZoneId.of("Europe/Stockholm");
        var firstPass = OffsetDateTime.parse("2026-10-25T02:30+02:00").toInstant();
        var secondPass = OffsetDateTime.parse("2026-10-25T02:30+01:00").toInstant();
        var beforeChange = new QuotaManager(redisTemplate, quotaDefinitionRepository, Clock.fixed(firstPass, zone),
                counterProperties);
        var afterChange = new QuotaManager(redisTemplate, quotaDefinitionRepository, Clock.fixed(secondPass, zone),
                counterProperties);
        var id = uniqueId();
        var config = new QuotaConfig(10, QuotaPeriod.HOURLY);

        var first = beforeChange.checkAndIncrement(id, config, 10);
        var second = afterChange.checkAndIncrement(id, config, 10);

        assertThat(first).as("first 02:30 (CEST)")
                .returns(true, QuotaStatus::allowed)
                .returns(OffsetDateTime.parse("2026-10-25T02:00+01:00").toInstant(), QuotaStatus::resetAt);
        assertThat(second).as("second 02:30 (CET) is a new hour with its own quota")
                .returns(true, QuotaStatus::allowed)
                .returns(10, QuotaStatus::used)
                .returns(OffsetDateTime.parse("2026-10-25T03:00+01:00").toInstant(), QuotaStatus::resetAt);
    }

    @Test
    void concurrentRequestsNeverExceedLimit() throws Exception {
        var id = uniqueId();
        var config = new QuotaConfig(50, QuotaPeriod.DAILY);
        var tasks = new ArrayList<Callable<QuotaStatus>>();
        for (var i = 0; i < 200; i++) {
            tasks.add(() -> quotaManager.checkAndIncrement(id, config, 1));
        }

        var allowed = 0;
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (var future : executor.invokeAll(tasks)) {
                if (future.get().allowed()) {
                    allowed++;
                }
            }
        }

        assertThat(allowed).isEqualTo(50);
        assertThat(usage(id, QuotaPeriod.DAILY)).isEqualTo(50);
    }

    @Test
    void resetQuotaClearsUsageSoRequestsAreAllowedAgain() {
        var id = defined(QuotaPeriod.DAILY, 100);
        var config = new QuotaConfig(100, QuotaPeriod.DAILY);
        quotaManager.checkAndIncrement(id, config, 100);

        quotaManager.resetQuota(id);

        assertThat(usage(id, QuotaPeriod.DAILY)).isZero();
        assertThat(quotaManager.checkAndIncrement(id, config, 100))
                .returns(true, QuotaStatus::allowed)
                .returns(100, QuotaStatus::used);
    }

    @Test
    void resetQuotaOnlyAffectsStoredPeriod() {
        var id = defined(QuotaPeriod.DAILY, 100);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 10);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.MONTHLY), 20);

        quotaManager.resetQuota(id);

        assertSoftly(softly -> {
            softly.assertThat(usage(id, QuotaPeriod.DAILY)).as("daily").isZero();
            softly.assertThat(usage(id, QuotaPeriod.MONTHLY)).as("monthly").isEqualTo(20);
        });
    }

    @Test
    void adjustUsageAddsAndSubtracts() {
        var id = defined(QuotaPeriod.DAILY, 100);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 50);

        assertThat(quotaManager.adjustUsage(id, -20)).isEqualTo(30);
        assertThat(quotaManager.adjustUsage(id, 5)).isEqualTo(35);
        assertThat(usage(id, QuotaPeriod.DAILY)).isEqualTo(35);
    }

    @Test
    void adjustUsageNeverGoesBelowZero() {
        var id = defined(QuotaPeriod.DAILY, 100);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 10);

        assertThat(quotaManager.adjustUsage(id, -100)).isZero();
        assertThat(usage(id, QuotaPeriod.DAILY)).isZero();
    }

    @Test
    void refundLetsPreviouslyRejectedRequestThrough() {
        var id = defined(QuotaPeriod.DAILY, 100);
        var config = new QuotaConfig(100, QuotaPeriod.DAILY);
        quotaManager.checkAndIncrement(id, config, 100);
        assertThat(quotaManager.checkAndIncrement(id, config, 10).allowed()).isFalse();

        quotaManager.adjustUsage(id, -10);

        assertThat(quotaManager.checkAndIncrement(id, config, 10))
                .returns(true, QuotaStatus::allowed)
                .returns(100, QuotaStatus::used);
    }

    @Test
    void adjustUsageOnNewIdStartsFromZeroAndExpiresWithPeriod() {
        var id = defined(QuotaPeriod.DAILY, 100);

        assertThat(quotaManager.adjustUsage(id, 3)).isEqualTo(3);

        assertExpiresSevenDaysAfterDailyReset(id);
    }

    @Test
    void setUsageOverwritesUsage() {
        var id = defined(QuotaPeriod.DAILY, 100);
        var config = new QuotaConfig(100, QuotaPeriod.DAILY);
        quotaManager.checkAndIncrement(id, config, 10);

        quotaManager.setUsage(id, 80);

        assertThat(usage(id, QuotaPeriod.DAILY)).isEqualTo(80);
        assertThat(quotaManager.checkAndIncrement(id, config, 30))
                .returns(false, QuotaStatus::allowed)
                .returns(80, QuotaStatus::used)
                .returns(20, QuotaStatus::remaining);
        assertExpiresSevenDaysAfterDailyReset(id);
    }

    @Test
    void setUsageRejectsNegativeUsage() {
        var id = defined(QuotaPeriod.DAILY, 100);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> quotaManager.setUsage(id, -1))
                .withMessageContaining("-1");
        assertThat(usage(id, QuotaPeriod.DAILY)).isZero();
    }

    @Test
    void getStatusReportsCurrentUsageWithoutConsumingQuota() {
        var id = uniqueId();
        var config = new QuotaConfig(100, QuotaPeriod.DAILY);
        var afterRequest = quotaManager.checkAndIncrement(id, config, 30);

        var status = quotaManager.getStatus(id, config);

        assertThat(status)
                .isEqualTo(afterRequest)
                .isEqualTo(new QuotaStatus(true, 100, 30, 70, OffsetDateTime.parse("2026-10-15T00:00+02:00").toInstant()));
        assertThat(quotaManager.getStatus(id, config)).as("repeated call").isEqualTo(status);
        assertThat(usage(id, QuotaPeriod.DAILY)).isEqualTo(30);
    }

    @Test
    void getStatusForUnknownIdReportsFullQuota() {
        var status = quotaManager.getStatus(uniqueId(), new QuotaConfig(100, QuotaPeriod.HOURLY));

        assertThat(status).isEqualTo(new QuotaStatus(true, 100, 0, 100, OffsetDateTime.parse("2026-10-14T11:00+02:00").toInstant()));
    }

    @Test
    void getStatusIsNotAllowedWhenQuotaIsUsedUp() {
        var id = uniqueId();
        var config = new QuotaConfig(100, QuotaPeriod.DAILY);
        quotaManager.checkAndIncrement(id, config, 100);

        assertThat(quotaManager.getStatus(id, config))
                .returns(false, QuotaStatus::allowed)
                .returns(100, QuotaStatus::used)
                .returns(0, QuotaStatus::remaining);
    }

    @Test
    void getStatusAfterSetUsageAboveLimitReportsNothingRemaining() {
        var id = defined(QuotaPeriod.DAILY, 100);
        quotaManager.setUsage(id, 150);

        assertThat(quotaManager.getStatus(id, new QuotaConfig(100, QuotaPeriod.DAILY)))
                .returns(false, QuotaStatus::allowed)
                .returns(150, QuotaStatus::used)
                .returns(0, QuotaStatus::remaining);
    }

    @Test
    void lastUsedIsRecordedWhenRequestIsAllowed() {
        var id = uniqueId();

        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 1);

        assertThat(quotaManager.getLastUsed(id)).contains(NOW.toInstant());
    }

    @Test
    void lastUsedIsEmptyForUnknownId() {
        assertThat(quotaManager.getLastUsed(uniqueId())).isEmpty();
    }

    @Test
    void lastUsedIsUpdatedByLaterRequestInAnyPeriod() {
        var id = uniqueId();
        var later = NOW.plusMinutes(5).toInstant();
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 1);

        managerAt(later).checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.MONTHLY), 1);

        assertThat(quotaManager.getLastUsed(id)).contains(later);
    }

    @Test
    void rejectedRequestDoesNotUpdateLastUsed() {
        var id = uniqueId();
        var config = new QuotaConfig(10, QuotaPeriod.DAILY);
        quotaManager.checkAndIncrement(id, config, 10);

        var rejected = managerAt(NOW.plusMinutes(5).toInstant()).checkAndIncrement(id, config, 1);

        assertThat(rejected.allowed()).isFalse();
        assertThat(quotaManager.getLastUsed(id)).contains(NOW.toInstant());
    }

    @Test
    void rejectedFirstRequestLeavesLastUsedEmpty() {
        var id = uniqueId();

        quotaManager.checkAndIncrement(id, new QuotaConfig(10, QuotaPeriod.DAILY), 11);

        assertThat(quotaManager.getLastUsed(id)).isEmpty();
    }

    @Test
    void resetQuotaKeepsLastUsed() {
        var id = defined(QuotaPeriod.DAILY, 100);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 1);

        quotaManager.resetQuota(id);

        assertThat(quotaManager.getLastUsed(id)).contains(NOW.toInstant());
    }

    @Test
    void deletesStaleUsage() {
        var kept = uniqueId();
        var daily = new QuotaConfig(100, QuotaPeriod.DAILY);
        quotaManager.checkAndIncrement(kept, daily, 3);
        managerAt(NOW.minusDays(1).toInstant()).checkAndIncrement(kept, daily, 1);
        quotaManager.checkAndIncrement(kept, new QuotaConfig(100, QuotaPeriod.HOURLY), 1);
        var other = uniqueId();
        quotaManager.checkAndIncrement(other, daily, 1);

        var deleted = quotaManager.deleteStaleUsage(Map.of(kept, QuotaPeriod.DAILY));

        // The current counter and last use of the kept quota stay
        assertThat(redisTemplate.opsForValue().get("quota:" + kept + ":daily:20261014")).isEqualTo("3");
        assertThat(redisTemplate.hasKey("quota:" + kept + ":last-used")).isTrue();
        // Yesterday's counter, the counter of another period, and everything of another id go
        assertThat(redisTemplate.hasKey("quota:" + kept + ":daily:20261013")).isFalse();
        assertThat(redisTemplate.hasKey("quota:" + kept + ":hourly:2026101410+0200")).isFalse();
        assertThat(redisTemplate.hasKey("quota:" + other + ":daily:20261014")).isFalse();
        assertThat(redisTemplate.hasKey("quota:" + other + ":last-used")).isFalse();
        // Keys left by other tests in the same Redis are stale too
        assertThat(deleted).isGreaterThanOrEqualTo(4);
    }

    @Test
    void readsUsageOfFinishedPeriods() {
        var id = uniqueId();
        var daily = new QuotaConfig(100, QuotaPeriod.DAILY);
        managerAt(NOW.minusDays(1).toInstant()).checkAndIncrement(id, daily, 4);
        managerAt(NOW.minusDays(3).toInstant()).checkAndIncrement(id, daily, 5);
        managerAt(NOW.minusHours(1).toInstant()).checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.HOURLY), 2);
        managerAt(NOW.minusMonths(1).toInstant()).checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.MONTHLY), 7);
        // The current period does not count
        quotaManager.checkAndIncrement(id, daily, 1);

        assertThat(quotaManager.getFinishedPeriodUsage(id, QuotaPeriod.DAILY)).containsExactly(
                new QuotaManager.PeriodUsage(Instant.parse("2026-10-10T22:00:00Z"), 5),
                new QuotaManager.PeriodUsage(Instant.parse("2026-10-12T22:00:00Z"), 4));
        assertThat(quotaManager.getFinishedPeriodUsage(id, QuotaPeriod.HOURLY))
                .containsExactly(new QuotaManager.PeriodUsage(Instant.parse("2026-10-14T07:00:00Z"), 2));
        // September ended more than the seven days of retention ago
        assertThat(quotaManager.getFinishedPeriodUsage(id, QuotaPeriod.MONTHLY)).isEmpty();
        assertThat(quotaManager.getFinishedPeriodUsage(id, QuotaPeriod.WEEKLY)).isEmpty();
    }

    @Test
    void readsFinishedHourlyPeriodsAcrossRepeatedDstHour() {
        // Clocks go back at 03:00 CEST on 2026-10-25, so the hour from 02:00 local time happens twice
        var id = uniqueId();
        var hourly = new QuotaConfig(100, QuotaPeriod.HOURLY);
        managerAt(Instant.parse("2026-10-25T00:30:00Z")).checkAndIncrement(id, hourly, 1);
        managerAt(Instant.parse("2026-10-25T01:30:00Z")).checkAndIncrement(id, hourly, 2);

        assertThat(managerAt(Instant.parse("2026-10-25T03:30:00Z")).getFinishedPeriodUsage(id, QuotaPeriod.HOURLY))
                .containsExactly(new QuotaManager.PeriodUsage(Instant.parse("2026-10-25T00:00:00Z"), 1),
                        new QuotaManager.PeriodUsage(Instant.parse("2026-10-25T01:00:00Z"), 2));
    }

    @Test
    void leavesOutPeriodsThatEndedBeforeCounterRetention() {
        var id = uniqueId();
        var daily = new QuotaConfig(100, QuotaPeriod.DAILY);
        // Both counters are still in Redis, which counts their expiry from its own clock, not the test's
        managerAt(NOW.minusDays(9).toInstant()).checkAndIncrement(id, daily, 3);
        managerAt(NOW.minusDays(7).toInstant()).checkAndIncrement(id, daily, 2);

        // The period of 2026-10-07 ended within the seven days; the one of 2026-10-05 did not
        assertThat(quotaManager.getFinishedPeriodUsage(id, QuotaPeriod.DAILY))
                .containsExactly(new QuotaManager.PeriodUsage(Instant.parse("2026-10-06T22:00:00Z"), 2));
    }

    @Test
    void keepsCountersSevenDaysByDefault() {
        assertThat(counterProperties.retention()).isEqualTo(Duration.ofDays(7));
        assertThat(bind(Map.of("quota.counter.retention", "30d")).retention()).isEqualTo(Duration.ofDays(30));
        assertThatExceptionOfType(BindException.class).isThrownBy(() -> bind(Map.of("quota.counter.retention", "0s")));
    }

    private static QuotaCounterProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("quota.counter", QuotaCounterProperties.class);
    }

    @Test
    void deletesAllUsageOfId() {
        var id = uniqueId();
        var daily = new QuotaConfig(100, QuotaPeriod.DAILY);
        quotaManager.checkAndIncrement(id, daily, 3);
        managerAt(NOW.minusDays(1).toInstant()).checkAndIncrement(id, daily, 1);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.HOURLY), 1);
        // An id that starts with the first one and a colon, and one with SCAN wildcards in it
        var longer = id + ":daily";
        quotaManager.checkAndIncrement(longer, daily, 1);
        var wildcard = id.substring(0, 10) + "*";
        quotaManager.checkAndIncrement(wildcard, daily, 1);

        assertThat(quotaManager.deleteUsage(id)).isEqualTo(4);

        assertThat(redisTemplate.keys("quota:" + id + ":*"))
                .containsExactlyInAnyOrder("quota:" + longer + ":daily:20261014", "quota:" + longer + ":last-used");
        assertThat(quotaManager.deleteUsage(wildcard)).isEqualTo(2);
        assertThat(quotaManager.getLastUsed(longer)).isPresent();
    }

    @Test
    void keepsDefinitionsWhenDeletingStaleUsage() {
        var id = defined(QuotaPeriod.DAILY, 100);

        quotaManager.deleteStaleUsage(Map.of());

        assertThat(quotaDefinitionRepository.existsById(id)).isTrue();
    }

    @Test
    void usageIsZeroBeforeFirstRequest() {
        assertThat(quotaManager.getUsage(defined(QuotaPeriod.MONTHLY, 100))).isZero();
    }

    @Test
    void usageOfStoredPeriodIsReported() {
        var id = defined(QuotaPeriod.WEEKLY, 100);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.WEEKLY), 7);
        quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 3);

        assertThat(quotaManager.getUsage(id)).isEqualTo(7);
    }

    @Test
    void perIdMethodsRequireStoredDefinition() {
        var id = uniqueId();

        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> quotaManager.getUsage(id)).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> quotaManager.resetQuota(id)).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> quotaManager.adjustUsage(id, 1)).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> quotaManager.setUsage(id, 1)).isInstanceOf(IllegalArgumentException.class);
        });
    }

    // Stores a quota for a new id; the per-id methods use its period
    private String defined(QuotaPeriod period, int limit) {
        var id = uniqueId();
        quotaDefinitionRepository.save(new QuotaDefinition(id, period, limit));
        return id;
    }

    // Usage in the given period, read through the explicit config, which needs no stored definition
    private int usage(String id, QuotaPeriod period) {
        return quotaManager.getStatus(id, new QuotaConfig(Integer.MAX_VALUE, period)).used();
    }

    private QuotaManager managerAt(Instant instant) {
        return new QuotaManager(redisTemplate, quotaDefinitionRepository, Clock.fixed(instant, NOW.getZone()),
                counterProperties);
    }

    private void assertExpiresSevenDaysAfterDailyReset(String id) {
        var reset = OffsetDateTime.parse("2026-10-15T00:00+02:00").toInstant();
        var expectedTtl = Duration.between(NOW.toInstant(), reset).plusDays(7).toSeconds();
        assertThat(redisTemplate.getExpire("quota:" + id + ":daily:20261014"))
                .as("TTL in seconds")
                .isBetween(expectedTtl - 4, expectedTtl);
    }

    private static String uniqueId() {
        return "test-id-" + UUID.randomUUID();
    }

}
