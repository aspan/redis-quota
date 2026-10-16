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

import org.jspecify.annotations.NonNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

/// Makes Redis match the users' quotas in PostgreSQL: copies them, so they come back after Redis has lost its
/// data, removes definitions that belong to no user, and deletes stale usage counters and last-used times.
///
/// Before that it records the users' finished periods in the usage history, and after it deletes history past
/// its retention. It runs at startup and then once a day, at `quota.sync.cron` ([QuotaSyncProperties]); the
/// history is also recorded every hour, so a finished period shows up soon after it ends. Counters outlive their
/// period by `quota.counter.retention`, so periods that ended while the application was down are recorded at
/// startup.
///
/// A Redis outage is logged rather than stopping the application, which fails open on quotas meanwhile, and the
/// next run tries again.
@Component
@EnableConfigurationProperties(QuotaSyncProperties.class)
public class UserQuotaSync implements ApplicationRunner, SchedulingConfigurer {

    /// Five minutes past every hour, so an hourly period is recorded soon after it ends.
    static final String HISTORY_CRON = "0 5 * * * *";

    private static final Logger LOGGER = LoggerFactory.getLogger(UserQuotaSync.class);

    private final UserAccountService userAccountService;
    private final QuotaHistoryService quotaHistoryService;
    private final QuotaSyncProperties properties;

    /// Creates the sync, scheduled by `properties`.
    public UserQuotaSync(UserAccountService userAccountService, QuotaHistoryService quotaHistoryService,
            QuotaSyncProperties properties) {
        this.userAccountService = userAccountService;
        this.quotaHistoryService = quotaHistoryService;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        sync();
    }

    /// Schedules the sync daily, at 03:30 by default, where `-` as `quota.sync.cron` turns it off, and the history
    /// every hour.
    @Override
    public void configureTasks(@NonNull ScheduledTaskRegistrar registrar) {
        if (properties.daily()) {
            registrar.addCronTask(this::sync, properties.cron());
        }
        registrar.addCronTask(this::recordHistory, HISTORY_CRON);
    }

    /// Records the finished periods, makes Redis match the users' quotas and deletes history past its retention,
    /// logging the outcome. A failure to record stops the sync before it deletes counters that are not yet in the
    /// history; a [DataAccessException] is logged rather than thrown.
    public void sync() {
        try {
            // First, as deleting stale counters also deletes those of finished periods
            var recorded = quotaHistoryService.recordFinishedPeriods();
            var result = userAccountService.syncQuotasToRedis();
            var expired = quotaHistoryService.deleteExpired();
            LOGGER.info("Recorded {} finished period(s), copied {} user quota(s) to Redis, removed {} definition(s) of "
                    + "no user and {} stale usage key(s), and deleted {} period(s) of history past its retention",
                    recorded, result.copied(), result.removed(), result.staleUsage(), expired);
        } catch (DataAccessException e) {
            // A failure to record stops the sync before it deletes counters that are not yet in the history
            LOGGER.error("Could not sync user quotas to Redis", e);
        }
    }

    /// Records the users' finished periods in the usage history; a [DataAccessException] is logged rather than
    /// thrown.
    public void recordHistory() {
        try {
            LOGGER.debug("Recorded {} finished period(s)", quotaHistoryService.recordFinishedPeriods());
        } catch (DataAccessException e) {
            LOGGER.error("Could not record the usage history", e);
        }
    }
}
