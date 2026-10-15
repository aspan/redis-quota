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

// Copies the users' quotas from PostgreSQL to Redis at startup, so they come back after Redis has lost its data.
// A Redis outage is logged rather than stopping the application, which fails open on quotas meanwhile
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
            var count = userAccountService.copyQuotasToRedis();
            LOGGER.info("Copied {} user quota(s) to Redis", count);
        } catch (DataAccessException e) {
            LOGGER.error("Could not copy user quotas to Redis", e);
        }
    }
}
