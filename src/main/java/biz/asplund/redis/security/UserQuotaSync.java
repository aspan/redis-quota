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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Makes Redis match the users' quotas in PostgreSQL: copies them, so they come back after Redis has lost its data,
// removes definitions that belong to no user, and deletes stale usage counters and last-used times. It runs at
// startup and then once a day, at quota.sync.cron. A Redis outage is logged rather than stopping the application,
// which fails open on quotas meanwhile, and the next run tries again
@Component
public class UserQuotaSync implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserQuotaSync.class);

    private final UserAccountService userAccountService;

    public UserQuotaSync(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @Override
    public void run(ApplicationArguments args) {
        sync();
    }

    // Daily at 03:30 by default (application.properties); "-" as quota.sync.cron turns the daily run off
    @Scheduled(cron = "${quota.sync.cron}")
    public void sync() {
        try {
            var result = userAccountService.syncQuotasToRedis();
            LOGGER.info("Copied {} user quota(s) to Redis, removed {} definition(s) of no user and {} stale usage key(s)",
                    result.copied(), result.removed(), result.staleUsage());
        } catch (DataAccessException e) {
            LOGGER.error("Could not sync user quotas to Redis", e);
        }
    }
}
