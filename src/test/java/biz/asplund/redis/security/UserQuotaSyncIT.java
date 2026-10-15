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

import biz.asplund.redis.quota.QuotaDefinition;
import biz.asplund.redis.quota.QuotaDefinitionRepository;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sync of the users' quotas to Redis, which runs at startup and once a day.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserQuotaSyncIT {

	@Autowired
	private ScheduledTaskHolder scheduledTaskHolder;

	@Autowired
	private UserQuotaSync userQuotaSync;

	@Autowired
	private QuotaDefinitionRepository quotaDefinitionRepository;

	@Test
	void runsDailyAtHalfPastThree() {
		// The sync is the application's only cron task
		assertThat(scheduledTaskHolder.getScheduledTasks())
				.map(scheduled -> scheduled.getTask())
				.filteredOn(CronTask.class::isInstance)
				.singleElement()
				.satisfies(task -> assertThat(((CronTask) task).getExpression()).isEqualTo("0 30 3 * * *"));
	}

	@Test
	void removesDefinitionsOfNoUserWhenRun() {
		var id = "no-user-" + UUID.randomUUID();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 5));

		userQuotaSync.sync();

		assertThat(quotaDefinitionRepository.existsById(id)).isFalse();
	}
}
