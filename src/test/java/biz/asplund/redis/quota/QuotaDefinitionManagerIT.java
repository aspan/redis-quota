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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

/**
 * Enforces quotas with limits stored per id through {@link QuotaDefinitionRepository}, using
 * {@link QuotaManager#checkAndIncrement(String, int)}.
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
		repository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 5));

		for (var i = 1; i <= 5; i++) {
			assertThat(quotaManager.checkAndIncrement(id, 1).allowed()).as("request %d", i).isTrue();
		}

		assertThat(quotaManager.checkAndIncrement(id, 1))
				.returns(false, QuotaStatus::allowed)
				.returns(5, QuotaStatus::limit)
				.returns(5, QuotaStatus::used)
				.returns(0, QuotaStatus::remaining);
	}

	@Test
	void storedLimitMatchesExplicitConfig() {
		var stored = uniqueId();
		var explicit = uniqueId();
		repository.save(new QuotaDefinition(stored, QuotaPeriod.DAILY, 100));

		var fromDefinition = quotaManager.checkAndIncrement(stored, 30);
		var fromConfig = quotaManager.checkAndIncrement(explicit, new QuotaConfig(100, QuotaPeriod.DAILY), 30);

		assertThat(fromDefinition).isEqualTo(fromConfig);
	}

	@Test
	void requestsCountInStoredPeriod() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 10));

		var status = quotaManager.checkAndIncrement(id, 3);

		assertSoftly(softly -> {
			softly.assertThat(status.resetAt()).as("reset").isEqualTo(Instant.parse("2026-10-14T09:00:00Z"));
			softly.assertThat(usage(id, QuotaPeriod.HOURLY)).as("hourly usage").isEqualTo(3);
			softly.assertThat(usage(id, QuotaPeriod.DAILY)).as("daily usage").isZero();
		});
	}

	@Test
	void changingStoredPeriodStartsCountingInNewPeriod() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 100));
		quotaManager.checkAndIncrement(id, 30);

		repository.save(new QuotaDefinition(id, QuotaPeriod.MONTHLY, 100));

		assertSoftly(softly -> {
			softly.assertThat(quotaManager.getStatus(id))
					.returns(0, QuotaStatus::used)
					.returns(Instant.parse("2026-10-31T23:00:00Z"), QuotaStatus::resetAt);
			softly.assertThat(usage(id, QuotaPeriod.DAILY)).as("earlier daily usage is kept").isEqualTo(30);
		});
	}

	@Test
	void raisingStoredLimitAllowsMoreRequestsWithoutResettingUsage() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 10));
		quotaManager.checkAndIncrement(id, 10);
		assertThat(quotaManager.checkAndIncrement(id, 1).allowed()).as("at original limit").isFalse();

		repository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 20));

		assertThat(quotaManager.checkAndIncrement(id, 5))
				.returns(true, QuotaStatus::allowed)
				.returns(20, QuotaStatus::limit)
				.returns(15, QuotaStatus::used)
				.returns(5, QuotaStatus::remaining);
	}

	@Test
	void loweringStoredLimitBelowUsageRejectsFurtherRequests() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 100));
		quotaManager.checkAndIncrement(id, 50);

		repository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 40));

		assertThat(quotaManager.checkAndIncrement(id, 1))
				.returns(false, QuotaStatus::allowed)
				.returns(50, QuotaStatus::used)
				.returns(0, QuotaStatus::remaining);
	}

	@Test
	void deletingDefinitionKeepsUsage() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 100));
		quotaManager.checkAndIncrement(id, 30);

		repository.deleteById(id);

		assertThat(repository.findById(id)).isEmpty();
		assertThat(usage(id, QuotaPeriod.DAILY)).isEqualTo(30);
	}

	@Test
	void resettingQuotaKeepsDefinition() {
		var id = uniqueId();
		var definition = new QuotaDefinition(id, QuotaPeriod.DAILY, 100);
		repository.save(definition);
		quotaManager.checkAndIncrement(id, 30);

		quotaManager.resetQuota(id);

		assertThat(repository.findById(id)).contains(definition);
		assertThat(quotaManager.checkAndIncrement(id, 100).allowed()).isTrue();
	}

	@Test
	void getStatusUsesStoredLimit() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 100));
		var afterRequest = quotaManager.checkAndIncrement(id, 40);

		assertThat(quotaManager.getStatus(id))
				.isEqualTo(afterRequest)
				.returns(100, QuotaStatus::limit)
				.returns(60, QuotaStatus::remaining);
	}

	@Test
	void getStatusForUndefinedIdIsRejected() {
		var id = uniqueId();

		assertThatIllegalArgumentException()
				.isThrownBy(() -> quotaManager.getStatus(id))
				.withMessage("No quota defined for id " + id);
	}

	@Test
	void undefinedIdIsRejected() {
		var id = uniqueId();

		assertThatIllegalArgumentException()
				.isThrownBy(() -> quotaManager.checkAndIncrement(id, 1))
				.withMessage("No quota defined for id " + id);
		assertThat(quotaManager.getLastUsed(id)).isEmpty();
	}

	@Test
	void definitionNeedsPeriodAndNonNegativeLimit() {
		assertThatNullPointerException().isThrownBy(() -> new QuotaDefinition("id", null, 10));
		assertThatIllegalArgumentException().isThrownBy(() -> new QuotaDefinition("id", QuotaPeriod.DAILY, -1));
	}

	// Usage in the given period, read through an explicit config, which needs no stored definition
	private int usage(String id, QuotaPeriod period) {
		return quotaManager.getStatus(id, new QuotaConfig(Integer.MAX_VALUE, period)).used();
	}

	private static String uniqueId() {
		return "test-id-" + UUID.randomUUID();
	}

}
