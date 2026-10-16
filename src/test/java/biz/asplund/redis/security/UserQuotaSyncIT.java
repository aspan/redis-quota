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
import biz.asplund.redis.quota.QuotaCounterProperties;
import biz.asplund.redis.quota.QuotaDefinition;
import biz.asplund.redis.quota.QuotaDefinitionRepository;
import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskHolder;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The sync of the users' quotas to Redis, which runs at startup and once a day, and the hourly usage history.
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

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private QuotaHistoryService quotaHistoryService;

    @Autowired
    private QuotaManager quotaManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private QuotaCounterProperties counterProperties;

    @Autowired
    private Clock clock;

    @Test
    void runsSyncDailyAndHistoryHourly() {
        // The application's only cron tasks
        assertThat(scheduledTaskHolder.getScheduledTasks())
                .map(scheduled -> scheduled.getTask())
                .filteredOn(CronTask.class::isInstance)
                .map(task -> ((CronTask) task).getExpression())
                .containsExactlyInAnyOrder("0 30 3 * * *", "0 5 * * * *");
    }

    @Test
    void recordsHistoryBeforeDeletingCountersOfFinishedPeriods() {
        var account = userAccountService.createUser("sync-" + UUID.randomUUID(), "password1", true, List.of(),
                new QuotaConfig(10, QuotaPeriod.DAILY));
        yesterday().checkAndIncrement(account.getUsername(), new QuotaConfig(10, QuotaPeriod.DAILY), 6);

        userQuotaSync.sync();

        assertThat(quotaHistoryService.findHistory(account.getUsername())).singleElement()
                .satisfies(record -> assertThat(record.getUsed()).isEqualTo(6));
        // The sync deleted yesterday's counter after recording it
        assertThat(quotaManager.getFinishedPeriodUsage(account.getUsername(), QuotaPeriod.DAILY)).isEmpty();
    }

    private QuotaManager yesterday() {
        return new QuotaManager(redisTemplate, quotaDefinitionRepository, Clock.offset(clock, Duration.ofDays(-1)),
                counterProperties);
    }

    @Test
    void removesDefinitionsOfNoUserWhenRun() {
        var id = "no-user-" + UUID.randomUUID();
        quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 5));

        userQuotaSync.sync();

        assertThat(quotaDefinitionRepository.existsById(id)).isFalse();
    }
}
