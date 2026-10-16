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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.data.annotation.Id;
import org.springframework.data.redis.core.RedisHash;

import java.util.Objects;

/// The quota of one id: a limit for a single period.
///
/// The constraints are for forms, which validate them with Vaadin's `BeanValidationBinder`; the constructor still
/// rejects a missing period and a negative limit.
///
/// @param id the id the quota is for, such as a username
/// @param period how often the usage resets
/// @param limit the most units that can be used in one period
@RedisHash("quota-definitions")
public record QuotaDefinition(
        @Id @NotBlank(message = "{quotaDefinition.id.notBlank}") String id,
        @NotNull(message = "{quotaDefinition.period.notNull}") QuotaPeriod period,
        @PositiveOrZero(message = "{quotaDefinition.limit.positiveOrZero}") int limit) {

    /// Rejects a missing period and a negative limit.
    ///
    /// @throws NullPointerException if `period` is `null`
    /// @throws IllegalArgumentException if `limit` is negative
    public QuotaDefinition {
        Objects.requireNonNull(period, "period");
        if (limit < 0) {
            throw new IllegalArgumentException("limit must not be negative: " + limit);
        }
    }

    /// Returns the limit and period to check usage against.
    public QuotaConfig config() {
        return new QuotaConfig(limit, period);
    }
}
