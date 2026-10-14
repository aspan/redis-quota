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

import biz.asplund.redis.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
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

		assertThat(quotaManager.getUsage(id, QuotaPeriod.MONTHLY)).isEqualTo(550);
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
		assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).isEqualTo(100);
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
		assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).isZero();
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
			softly.assertThat(quotaManager.getUsage(alice, QuotaPeriod.DAILY)).as("alice").isEqualTo(100);
			softly.assertThat(quotaManager.getUsage(bob, QuotaPeriod.DAILY)).as("bob").isEqualTo(30);
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
			softly.assertThat(quotaManager.getUsage(id, QuotaPeriod.HOURLY)).as("hourly").isEqualTo(5);
			softly.assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).as("daily").isEqualTo(10);
			softly.assertThat(quotaManager.getUsage(id, QuotaPeriod.WEEKLY)).as("weekly").isEqualTo(20);
			softly.assertThat(quotaManager.getUsage(id, QuotaPeriod.MONTHLY)).as("monthly").isEqualTo(30);
			softly.assertThat(quotaManager.getUsage(id, QuotaPeriod.YEARLY)).as("yearly").isEqualTo(40);
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
	void usageIsStoredUnderPeriodKeyExpiringOneHourAfterReset(QuotaPeriod period, String keySuffix,
			OffsetDateTime reset) {
		var id = uniqueId();
		var key = "quota:" + id + ":" + keySuffix;

		quotaManager.checkAndIncrement(id, new QuotaConfig(1000, period), 7);

		var expectedTtl = Duration.between(NOW.toInstant(), reset.toInstant()).plusHours(1).toSeconds();
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
		var beforeChange = new QuotaManager(redisTemplate, quotaDefinitionRepository, Clock.fixed(firstPass, zone));
		var afterChange = new QuotaManager(redisTemplate, quotaDefinitionRepository, Clock.fixed(secondPass, zone));
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
		assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).isEqualTo(50);
	}

	@Test
	void resetQuotaClearsUsageSoRequestsAreAllowedAgain() {
		var id = uniqueId();
		var config = new QuotaConfig(100, QuotaPeriod.DAILY);
		quotaManager.checkAndIncrement(id, config, 100);

		quotaManager.resetQuota(id, QuotaPeriod.DAILY);

		assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).isZero();
		assertThat(quotaManager.checkAndIncrement(id, config, 100))
				.returns(true, QuotaStatus::allowed)
				.returns(100, QuotaStatus::used);
	}

	@Test
	void resetQuotaOnlyAffectsGivenPeriod() {
		var id = uniqueId();
		quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 10);
		quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.MONTHLY), 20);

		quotaManager.resetQuota(id, QuotaPeriod.DAILY);

		assertSoftly(softly -> {
			softly.assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).as("daily").isZero();
			softly.assertThat(quotaManager.getUsage(id, QuotaPeriod.MONTHLY)).as("monthly").isEqualTo(20);
		});
	}

	@Test
	void adjustUsageAddsAndSubtracts() {
		var id = uniqueId();
		quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 50);

		assertThat(quotaManager.adjustUsage(id, QuotaPeriod.DAILY, -20)).isEqualTo(30);
		assertThat(quotaManager.adjustUsage(id, QuotaPeriod.DAILY, 5)).isEqualTo(35);
		assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).isEqualTo(35);
	}

	@Test
	void adjustUsageNeverGoesBelowZero() {
		var id = uniqueId();
		quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 10);

		assertThat(quotaManager.adjustUsage(id, QuotaPeriod.DAILY, -100)).isZero();
		assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).isZero();
	}

	@Test
	void refundLetsPreviouslyRejectedRequestThrough() {
		var id = uniqueId();
		var config = new QuotaConfig(100, QuotaPeriod.DAILY);
		quotaManager.checkAndIncrement(id, config, 100);
		assertThat(quotaManager.checkAndIncrement(id, config, 10).allowed()).isFalse();

		quotaManager.adjustUsage(id, QuotaPeriod.DAILY, -10);

		assertThat(quotaManager.checkAndIncrement(id, config, 10))
				.returns(true, QuotaStatus::allowed)
				.returns(100, QuotaStatus::used);
	}

	@Test
	void adjustUsageOnNewIdStartsFromZeroAndExpiresWithPeriod() {
		var id = uniqueId();

		assertThat(quotaManager.adjustUsage(id, QuotaPeriod.DAILY, 3)).isEqualTo(3);

		assertExpiresOneHourAfterDailyReset(id);
	}

	@Test
	void setUsageOverwritesUsage() {
		var id = uniqueId();
		var config = new QuotaConfig(100, QuotaPeriod.DAILY);
		quotaManager.checkAndIncrement(id, config, 10);

		quotaManager.setUsage(id, QuotaPeriod.DAILY, 80);

		assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).isEqualTo(80);
		assertThat(quotaManager.checkAndIncrement(id, config, 30))
				.returns(false, QuotaStatus::allowed)
				.returns(80, QuotaStatus::used)
				.returns(20, QuotaStatus::remaining);
		assertExpiresOneHourAfterDailyReset(id);
	}

	@Test
	void setUsageRejectsNegativeUsage() {
		var id = uniqueId();

		assertThatIllegalArgumentException()
				.isThrownBy(() -> quotaManager.setUsage(id, QuotaPeriod.DAILY, -1))
				.withMessageContaining("-1");
		assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).isZero();
	}

	@Test
	void usageIsZeroForUnknownUser() {
		assertThat(quotaManager.getUsage(uniqueId(), QuotaPeriod.MONTHLY)).isZero();
	}

	private void assertExpiresOneHourAfterDailyReset(String id) {
		var reset = OffsetDateTime.parse("2026-10-15T00:00+02:00").toInstant();
		var expectedTtl = Duration.between(NOW.toInstant(), reset).plusHours(1).toSeconds();
		assertThat(redisTemplate.getExpire("quota:" + id + ":daily:20261014"))
				.as("TTL in seconds")
				.isBetween(expectedTtl - 4, expectedTtl);
	}

	private static String uniqueId() {
		return "test-id-" + UUID.randomUUID();
	}

}
