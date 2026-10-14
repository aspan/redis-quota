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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Enforces quotas with limits stored per id through {@link QuotaDefinitionRepository}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class QuotaDefinitionManagerIT {

	@Autowired
	private QuotaDefinitionRepository repository;

	@Autowired
	private QuotaManager quotaManager;

	@TestBean
	private Clock clock;

	static Clock clock() {
		// Mid-hour, so hourly quotas do not roll over during the test
		return Clock.fixed(Instant.parse("2026-10-14T08:15:30Z"), ZoneId.of("Europe/Stockholm"));
	}

	@Test
	void storedLimitIsEnforced() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.HOURLY, 5)));

		for (var i = 1; i <= 5; i++) {
			assertThat(check(id, QuotaPeriod.HOURLY, 1).allowed()).as("request %d", i).isTrue();
		}

		assertThat(check(id, QuotaPeriod.HOURLY, 1))
				.returns(false, QuotaStatus::allowed)
				.returns(5, QuotaStatus::limit)
				.returns(5, QuotaStatus::used)
				.returns(0, QuotaStatus::remaining);
	}

	@Test
	void eachPeriodUsesItsOwnStoredLimit() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.HOURLY, 10, QuotaPeriod.DAILY, 100)));

		var hourly = check(id, QuotaPeriod.HOURLY, 8);
		var daily = check(id, QuotaPeriod.DAILY, 8);

		assertSoftly(softly -> {
			softly.assertThat(hourly.remaining()).as("hourly remaining").isEqualTo(2);
			softly.assertThat(daily.remaining()).as("daily remaining").isEqualTo(92);
		});
	}

	@Test
	void raisingStoredLimitAllowsMoreRequestsWithoutResettingUsage() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.DAILY, 10)));
		check(id, QuotaPeriod.DAILY, 10);
		assertThat(check(id, QuotaPeriod.DAILY, 1).allowed()).as("at original limit").isFalse();

		repository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.DAILY, 20)));

		assertThat(check(id, QuotaPeriod.DAILY, 5))
				.returns(true, QuotaStatus::allowed)
				.returns(20, QuotaStatus::limit)
				.returns(15, QuotaStatus::used)
				.returns(5, QuotaStatus::remaining);
	}

	@Test
	void loweringStoredLimitBelowUsageRejectsFurtherRequests() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.DAILY, 100)));
		check(id, QuotaPeriod.DAILY, 50);

		repository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.DAILY, 40)));

		assertThat(check(id, QuotaPeriod.DAILY, 1))
				.returns(false, QuotaStatus::allowed)
				.returns(50, QuotaStatus::used)
				.returns(0, QuotaStatus::remaining);
	}

	@Test
	void deletingDefinitionKeepsUsage() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.DAILY, 100)));
		check(id, QuotaPeriod.DAILY, 30);

		repository.deleteById(id);

		assertThat(repository.findById(id)).isEmpty();
		assertThat(quotaManager.getUsage(id, QuotaPeriod.DAILY)).isEqualTo(30);
	}

	@Test
	void resettingQuotaKeepsDefinition() {
		var id = uniqueId();
		var definition = new QuotaDefinition(id, Map.of(QuotaPeriod.DAILY, 100));
		repository.save(definition);
		check(id, QuotaPeriod.DAILY, 30);

		quotaManager.resetQuota(id, QuotaPeriod.DAILY);

		assertThat(repository.findById(id)).contains(definition);
		assertThat(check(id, QuotaPeriod.DAILY, 100).allowed()).isTrue();
	}

	private QuotaStatus check(String id, QuotaPeriod period, int amount) {
		var limit = repository.findById(id)
				.map(definition -> definition.limits().get(period))
				.orElseThrow();
		return quotaManager.checkAndIncrement(id, new QuotaConfig(limit, period), amount);
	}

	private static String uniqueId() {
		return "test-id-" + UUID.randomUUID();
	}

}
