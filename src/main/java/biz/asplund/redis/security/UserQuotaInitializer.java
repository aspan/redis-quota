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
import org.springframework.stereotype.Component;

// Makes Redis match the users' quotas in PostgreSQL at startup: copies them, so they come back after Redis has lost
// its data, removes definitions that belong to no user, and deletes stale usage counters and last-used times. A Redis outage is logged
// rather than stopping the application, which fails open on quotas meanwhile
@Component
public class UserQuotaInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(UserQuotaInitializer.class);

    private final UserAccountService userAccountService;

    public UserQuotaInitializer(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            var result = userAccountService.syncQuotasToRedis();
            LOGGER.info("Copied {} user quota(s) to Redis, removed {} definition(s) of no user and {} stale usage key(s)",
                    result.copied(), result.removed(), result.staleUsage());
        } catch (DataAccessException e) {
            LOGGER.error("Could not sync user quotas to Redis", e);
        }
    }
}
