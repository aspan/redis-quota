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

import java.time.Duration;

/// The history of the users' quota usage.
///
/// @param retention how long the usage of a finished period is kept, counted from the start of the period
@ConfigurationProperties("quota.history")
public record QuotaHistoryProperties(@DefaultValue("365d") Duration retention) {

    /// @throws IllegalArgumentException if `retention` is not positive
    public QuotaHistoryProperties {
        if (retention.isNegative() || retention.isZero()) {
            throw new IllegalArgumentException("quota.history.retention must be positive: " + retention);
        }
    }
}
