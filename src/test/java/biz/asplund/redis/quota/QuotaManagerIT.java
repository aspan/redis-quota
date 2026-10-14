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

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class QuotaManagerIT {

	@Autowired
	private QuotaManager quotaManager;

	@Autowired
	private StringRedisTemplate redisTemplate;

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
		assertAll("first request (250)",
				() -> assertTrue(first.allowed()),
				() -> assertEquals(250, first.used()),
				() -> assertEquals(750, first.remaining()));

		var second = quotaManager.checkAndIncrement(id, config, 300);
		assertAll("second request (300)",
				() -> assertTrue(second.allowed()),
				() -> assertEquals(550, second.used()),
				() -> assertEquals(450, second.remaining()));

		var third = quotaManager.checkAndIncrement(id, config, 500);
		assertAll("third request (500) exceeds limit",
				() -> assertFalse(third.allowed()),
				() -> assertEquals(550, third.used()),
				() -> assertEquals(450, third.remaining()));

		assertEquals(550, quotaManager.getUsage(id, QuotaPeriod.MONTHLY));
	}

	@Test
	void requestReachingExactLimitIsAllowed() {
		var id = uniqueId();
		var config = new QuotaConfig(100, QuotaPeriod.DAILY);

		var status = quotaManager.checkAndIncrement(id, config, 100);
		assertAll(
				() -> assertTrue(status.allowed()),
				() -> assertEquals(100, status.used()),
				() -> assertEquals(0, status.remaining()));

		var next = quotaManager.checkAndIncrement(id, config, 1);
		assertFalse(next.allowed());
		assertEquals(0, next.remaining());
		assertEquals(100, quotaManager.getUsage(id, QuotaPeriod.DAILY));
	}

	@Test
	void rejectedRequestDoesNotConsumeQuota() {
		var id = uniqueId();
		var config = new QuotaConfig(100, QuotaPeriod.DAILY);

		quotaManager.checkAndIncrement(id, config, 60);
		var rejected = quotaManager.checkAndIncrement(id, config, 50);
		assertAll("rejected request reports what is actually left",
				() -> assertFalse(rejected.allowed()),
				() -> assertEquals(60, rejected.used()),
				() -> assertEquals(40, rejected.remaining()));

		var status = quotaManager.checkAndIncrement(id, config, 40);
		assertAll("smaller request still fits after rejection",
				() -> assertTrue(status.allowed()),
				() -> assertEquals(100, status.used()),
				() -> assertEquals(0, status.remaining()));
	}

	@Test
	void requestLargerThanLimitIsRejectedForFreshUser() {
		var id = uniqueId();
		var config = new QuotaConfig(10, QuotaPeriod.DAILY);

		var status = quotaManager.checkAndIncrement(id, config, 11);

		assertAll(
				() -> assertFalse(status.allowed()),
				() -> assertEquals(0, status.used()),
				() -> assertEquals(10, status.remaining()));
		assertEquals(0, quotaManager.getUsage(id, QuotaPeriod.DAILY));
	}

	@Test
	void usersHaveIndependentQuotas() {
		var alice = uniqueId();
		var bob = uniqueId();
		var config = new QuotaConfig(100, QuotaPeriod.DAILY);

		quotaManager.checkAndIncrement(alice, config, 100);
		var bobStatus = quotaManager.checkAndIncrement(bob, config, 30);

		assertAll(
				() -> assertTrue(bobStatus.allowed()),
				() -> assertEquals(30, bobStatus.used()),
				() -> assertEquals(100, quotaManager.getUsage(alice, QuotaPeriod.DAILY)),
				() -> assertEquals(30, quotaManager.getUsage(bob, QuotaPeriod.DAILY)));
	}

	@Test
	void periodsAreTrackedIndependently() {
		var id = uniqueId();

		quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.HOURLY), 5);
		quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.DAILY), 10);
		quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.WEEKLY), 20);
		quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.MONTHLY), 30);
		quotaManager.checkAndIncrement(id, new QuotaConfig(100, QuotaPeriod.YEARLY), 40);

		assertAll(
				() -> assertEquals(5, quotaManager.getUsage(id, QuotaPeriod.HOURLY)),
				() -> assertEquals(10, quotaManager.getUsage(id, QuotaPeriod.DAILY)),
				() -> assertEquals(20, quotaManager.getUsage(id, QuotaPeriod.WEEKLY)),
				() -> assertEquals(30, quotaManager.getUsage(id, QuotaPeriod.MONTHLY)),
				() -> assertEquals(40, quotaManager.getUsage(id, QuotaPeriod.YEARLY)));
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

		assertEquals(expectedReset.toInstant(), status.resetAt());
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
		assertAll(
				() -> assertEquals("7", redisTemplate.opsForValue().get(key)),
				() -> assertTrue(ttl > expectedTtl - 5 && ttl <= expectedTtl,
						"expected TTL close to " + expectedTtl + "s but was " + ttl + "s"));
	}

	@Test
	void hourlyQuotaKeepsRepeatedDstHourSeparate() {
		// Clocks go back at 03:00 CEST on 2026-10-25, so 02:30 local time happens twice
		var zone = ZoneId.of("Europe/Stockholm");
		var firstPass = OffsetDateTime.parse("2026-10-25T02:30+02:00").toInstant();
		var secondPass = OffsetDateTime.parse("2026-10-25T02:30+01:00").toInstant();
		var beforeChange = new QuotaManager(redisTemplate, Clock.fixed(firstPass, zone));
		var afterChange = new QuotaManager(redisTemplate, Clock.fixed(secondPass, zone));
		var id = uniqueId();
		var config = new QuotaConfig(10, QuotaPeriod.HOURLY);

		var first = beforeChange.checkAndIncrement(id, config, 10);
		var second = afterChange.checkAndIncrement(id, config, 10);

		assertAll(
				() -> assertTrue(first.allowed()),
				() -> assertEquals(OffsetDateTime.parse("2026-10-25T02:00+01:00").toInstant(), first.resetAt()),
				() -> assertTrue(second.allowed(), "second 02:30 is a new hour with its own quota"),
				() -> assertEquals(10, second.used()),
				() -> assertEquals(OffsetDateTime.parse("2026-10-25T03:00+01:00").toInstant(), second.resetAt()));
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

		assertEquals(50, allowed);
		assertEquals(50, quotaManager.getUsage(id, QuotaPeriod.DAILY));
	}

	@Test
	void usageIsZeroForUnknownUser() {
		assertEquals(0, quotaManager.getUsage(uniqueId(), QuotaPeriod.MONTHLY));
	}

	private static String uniqueId() {
		return "test-id-" + UUID.randomUUID();
	}

}
