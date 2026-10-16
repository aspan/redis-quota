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

import java.time.Instant;

/// The state of a quota in its current period, as checked or read by [QuotaManager].
///
/// @param allowed   for a check, whether the amount fit and was counted, or was let through because Redis could not be
///                reached; for a status that only reads, whether at least one unit is left
/// @param limit     the most units that can be used in the period
/// @param used      the units used in the period
/// @param remaining the units left in the period, never negative
/// @param resetAt   when the current period ends and the usage starts over
public record QuotaStatus(boolean allowed, int limit, int used, int remaining, Instant resetAt) {
}
