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

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/// The usage counters in Redis.
///
/// @param retention how long the counter of a period is kept after the period ends, so the usage history can still
///                  record it when the application was down when the period ended
@ConfigurationProperties("quota.counter")
public record QuotaCounterProperties(@DefaultValue("7d") Duration retention) {

    /// Rejects a `quota.counter.retention` that is not positive.
    public QuotaCounterProperties {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("quota.counter.retention must be positive: " + retention);
        }
    }
}
