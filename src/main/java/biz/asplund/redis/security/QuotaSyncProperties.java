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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;

/// The daily sync of the users' quotas to Redis, which also runs at startup.
///
/// @param cron when the daily sync runs: a Spring cron expression in the server's time zone, or `-` to run it only
///             at startup
@ConfigurationProperties("quota.sync")
public record QuotaSyncProperties(@DefaultValue("0 30 3 * * *") String cron) {

    /// @throws IllegalArgumentException if `cron` is neither a cron expression nor `-`
    public QuotaSyncProperties {
        if (!Scheduled.CRON_DISABLED.equals(cron) && !CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException(
                    "quota.sync.cron must be a cron expression or " + Scheduled.CRON_DISABLED + ": " + cron);
        }
    }

    /// Returns whether the daily sync runs, that is whether `cron` is not `-`.
    boolean daily() {
        return !Scheduled.CRON_DISABLED.equals(cron);
    }
}
