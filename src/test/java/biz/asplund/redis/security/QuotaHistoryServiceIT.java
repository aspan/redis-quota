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

package biz.asplund.redis.security;

import biz.asplund.redis.quota.QuotaConfig;
import biz.asplund.redis.quota.QuotaDefinitionRepository;
import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * The usage history: recording each user's finished periods from Redis, and deleting old ones.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class QuotaHistoryServiceIT {

	@Autowired
	private QuotaHistoryService quotaHistoryService;

	@Autowired
	private QuotaUsageRecordRepository quotaUsageRecordRepository;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private QuotaDefinitionRepository quotaDefinitionRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private Clock clock;

	@Test
	void recordsPreviousPeriodOfUserQuota() {
		var account = createUser(new QuotaConfig(10, QuotaPeriod.DAILY));
		yesterday().checkAndIncrement(account.getUsername(), new QuotaConfig(10, QuotaPeriod.DAILY), 4);

		quotaHistoryService.recordPreviousPeriods();

		assertThat(quotaHistoryService.findHistory(account.getUsername())).singleElement().satisfies(record -> {
			assertThat(record.getPeriod()).isEqualTo(QuotaPeriod.DAILY);
			assertThat(record.getUsed()).isEqualTo(4);
			assertThat(record.getQuotaLimit()).isEqualTo(10);
		});
	}

	@Test
	void updatesPeriodWhenRecordedAgain() {
		var account = createUser(new QuotaConfig(10, QuotaPeriod.DAILY));
		yesterday().checkAndIncrement(account.getUsername(), new QuotaConfig(10, QuotaPeriod.DAILY), 4);
		quotaHistoryService.recordPreviousPeriods();

		yesterday().checkAndIncrement(account.getUsername(), new QuotaConfig(10, QuotaPeriod.DAILY), 3);
		quotaHistoryService.recordPreviousPeriods();

		assertThat(quotaHistoryService.findHistory(account.getUsername())).singleElement()
				.satisfies(record -> assertThat(record.getUsed()).isEqualTo(7));
	}

	@Test
	void recordsNothingWithoutUsageOrQuota() {
		var unused = createUser(new QuotaConfig(10, QuotaPeriod.DAILY));
		var withoutQuota = createUser(null);

		quotaHistoryService.recordPreviousPeriods();

		assertThat(quotaHistoryService.findHistory(unused.getUsername())).isEmpty();
		assertThat(quotaHistoryService.findHistory(withoutQuota.getUsername())).isEmpty();
	}

	@Test
	void deletesPeriodsPastRetention() {
		var account = createUser(new QuotaConfig(10, QuotaPeriod.DAILY));
		var old = new QuotaUsageRecord(account, QuotaPeriod.DAILY, clock.instant().minus(Duration.ofDays(400)));
		old.update(5, 10);
		var recent = new QuotaUsageRecord(account, QuotaPeriod.DAILY, clock.instant().minus(Duration.ofDays(2)));
		recent.update(3, 10);
		quotaUsageRecordRepository.saveAll(List.of(old, recent));

		assertThat(quotaHistoryService.deleteExpired()).isPositive();

		assertThat(quotaHistoryService.findHistory(account.getUsername())).extracting(QuotaUsageRecord::getUsed).containsExactly(3);
	}

	@Test
	void deletesHistoryWithUser() {
		var account = createUser(new QuotaConfig(10, QuotaPeriod.DAILY));
		yesterday().checkAndIncrement(account.getUsername(), new QuotaConfig(10, QuotaPeriod.DAILY), 1);
		quotaHistoryService.recordPreviousPeriods();

		userAccountService.deleteUser(account.getId(), "someone-else");

		assertThat(quotaHistoryService.findHistory(account.getUsername())).isEmpty();
	}

	@Test
	void keepsOneYearByDefault() {
		assertThat(bind(Map.of()).retention()).isEqualTo(Duration.ofDays(365));
		assertThat(bind(Map.of("quota.history.retention", "90d")).retention()).isEqualTo(Duration.ofDays(90));
		assertThatExceptionOfType(BindException.class)
				.isThrownBy(() -> bind(Map.of("quota.history.retention", "0d")));
	}

	private UserAccount createUser(QuotaConfig quota) {
		return userAccountService.createUser("history-" + UUID.randomUUID(), "password1", true, List.of(), quota);
	}

	// Counts in the period before the current one, as the counters of finished periods were written
	private QuotaManager yesterday() {
		return new QuotaManager(redisTemplate, quotaDefinitionRepository, Clock.offset(clock, Duration.ofDays(-1)));
	}

	private static QuotaHistoryProperties bind(Map<String, String> values) {
		return new Binder(new MapConfigurationPropertySource(values))
				.bindOrCreate("quota.history", QuotaHistoryProperties.class);
	}
}
