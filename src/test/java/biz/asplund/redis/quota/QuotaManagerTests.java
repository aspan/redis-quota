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
class QuotaManagerTests {

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
		var userId = uniqueUser();
		var config = new QuotaConfig(1000, QuotaPeriod.MONTHLY);

		var first = quotaManager.checkAndIncrement(userId, config, 250);
		assertAll("first request (250)",
				() -> assertTrue(first.isAllowed()),
				() -> assertEquals(250, first.getUsed()),
				() -> assertEquals(750, first.getRemaining()));

		var second = quotaManager.checkAndIncrement(userId, config, 300);
		assertAll("second request (300)",
				() -> assertTrue(second.isAllowed()),
				() -> assertEquals(550, second.getUsed()),
				() -> assertEquals(450, second.getRemaining()));

		var third = quotaManager.checkAndIncrement(userId, config, 500);
		assertAll("third request (500) exceeds limit",
				() -> assertFalse(third.isAllowed()),
				() -> assertEquals(550, third.getUsed()),
				() -> assertEquals(450, third.getRemaining()));

		assertEquals(550, quotaManager.getUsage(userId, QuotaPeriod.MONTHLY));
	}

	@Test
	void requestReachingExactLimitIsAllowed() {
		var userId = uniqueUser();
		var config = new QuotaConfig(100, QuotaPeriod.DAILY);

		var status = quotaManager.checkAndIncrement(userId, config, 100);
		assertAll(
				() -> assertTrue(status.isAllowed()),
				() -> assertEquals(100, status.getUsed()),
				() -> assertEquals(0, status.getRemaining()));

		var next = quotaManager.checkAndIncrement(userId, config, 1);
		assertFalse(next.isAllowed());
		assertEquals(0, next.getRemaining());
		assertEquals(100, quotaManager.getUsage(userId, QuotaPeriod.DAILY));
	}

	@Test
	void rejectedRequestDoesNotConsumeQuota() {
		var userId = uniqueUser();
		var config = new QuotaConfig(100, QuotaPeriod.DAILY);

		quotaManager.checkAndIncrement(userId, config, 60);
		var rejected = quotaManager.checkAndIncrement(userId, config, 50);
		assertAll("rejected request reports what is actually left",
				() -> assertFalse(rejected.isAllowed()),
				() -> assertEquals(60, rejected.getUsed()),
				() -> assertEquals(40, rejected.getRemaining()));

		var status = quotaManager.checkAndIncrement(userId, config, 40);
		assertAll("smaller request still fits after rejection",
				() -> assertTrue(status.isAllowed()),
				() -> assertEquals(100, status.getUsed()),
				() -> assertEquals(0, status.getRemaining()));
	}

	@Test
	void requestLargerThanLimitIsRejectedForFreshUser() {
		var userId = uniqueUser();
		var config = new QuotaConfig(10, QuotaPeriod.DAILY);

		var status = quotaManager.checkAndIncrement(userId, config, 11);

		assertAll(
				() -> assertFalse(status.isAllowed()),
				() -> assertEquals(0, status.getUsed()),
				() -> assertEquals(10, status.getRemaining()));
		assertEquals(0, quotaManager.getUsage(userId, QuotaPeriod.DAILY));
	}

	@Test
	void usersHaveIndependentQuotas() {
		var alice = uniqueUser();
		var bob = uniqueUser();
		var config = new QuotaConfig(100, QuotaPeriod.DAILY);

		quotaManager.checkAndIncrement(alice, config, 100);
		var bobStatus = quotaManager.checkAndIncrement(bob, config, 30);

		assertAll(
				() -> assertTrue(bobStatus.isAllowed()),
				() -> assertEquals(30, bobStatus.getUsed()),
				() -> assertEquals(100, quotaManager.getUsage(alice, QuotaPeriod.DAILY)),
				() -> assertEquals(30, quotaManager.getUsage(bob, QuotaPeriod.DAILY)));
	}

	@Test
	void periodsAreTrackedIndependently() {
		var userId = uniqueUser();

		quotaManager.checkAndIncrement(userId, new QuotaConfig(100, QuotaPeriod.DAILY), 10);
		quotaManager.checkAndIncrement(userId, new QuotaConfig(100, QuotaPeriod.WEEKLY), 20);
		quotaManager.checkAndIncrement(userId, new QuotaConfig(100, QuotaPeriod.MONTHLY), 30);
		quotaManager.checkAndIncrement(userId, new QuotaConfig(100, QuotaPeriod.YEARLY), 40);

		assertAll(
				() -> assertEquals(10, quotaManager.getUsage(userId, QuotaPeriod.DAILY)),
				() -> assertEquals(20, quotaManager.getUsage(userId, QuotaPeriod.WEEKLY)),
				() -> assertEquals(30, quotaManager.getUsage(userId, QuotaPeriod.MONTHLY)),
				() -> assertEquals(40, quotaManager.getUsage(userId, QuotaPeriod.YEARLY)));
	}

	@ParameterizedTest
	@CsvSource({
			"DAILY,   2026-10-15T00:00+02:00",
			"WEEKLY,  2026-10-19T00:00+02:00",
			"MONTHLY, 2026-11-01T00:00+01:00",
			"YEARLY,  2027-01-01T00:00+01:00"
	})
	void resetTimeIsStartOfNextPeriodInClockZone(QuotaPeriod period, OffsetDateTime expectedReset) {
		var config = new QuotaConfig(1000, period);

		var status = quotaManager.checkAndIncrement(uniqueUser(), config, 1);

		assertEquals(expectedReset.toInstant(), status.getResetAt());
	}

	@ParameterizedTest
	@CsvSource({
			"DAILY,   daily:20261014,  2026-10-15T00:00+02:00",
			"WEEKLY,  weekly:20261012, 2026-10-19T00:00+02:00",
			"MONTHLY, monthly:202610,  2026-11-01T00:00+01:00",
			"YEARLY,  yearly:2026,     2027-01-01T00:00+01:00"
	})
	void usageIsStoredUnderPeriodKeyExpiringOneHourAfterReset(QuotaPeriod period, String keySuffix,
			OffsetDateTime reset) {
		var userId = uniqueUser();
		var key = "quota:" + userId + ":" + keySuffix;

		quotaManager.checkAndIncrement(userId, new QuotaConfig(1000, period), 7);

		var expectedTtl = Duration.between(NOW.toInstant(), reset.toInstant()).plusHours(1).toSeconds();
		var ttl = redisTemplate.getExpire(key);
		assertAll(
				() -> assertEquals("7", redisTemplate.opsForValue().get(key)),
				() -> assertTrue(ttl > expectedTtl - 5 && ttl <= expectedTtl,
						"expected TTL close to " + expectedTtl + "s but was " + ttl + "s"));
	}

	@Test
	void concurrentRequestsNeverExceedLimit() throws Exception {
		var userId = uniqueUser();
		var config = new QuotaConfig(50, QuotaPeriod.DAILY);
		var tasks = new ArrayList<Callable<QuotaStatus>>();
		for (var i = 0; i < 200; i++) {
			tasks.add(() -> quotaManager.checkAndIncrement(userId, config, 1));
		}

		var allowed = 0;
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (var future : executor.invokeAll(tasks)) {
				if (future.get().isAllowed()) {
					allowed++;
				}
			}
		}

		assertEquals(50, allowed);
		assertEquals(50, quotaManager.getUsage(userId, QuotaPeriod.DAILY));
	}

	@Test
	void usageIsZeroForUnknownUser() {
		assertEquals(0, quotaManager.getUsage(uniqueUser(), QuotaPeriod.MONTHLY));
	}

	private static String uniqueUser() {
		return "test-user-" + UUID.randomUUID();
	}

}
