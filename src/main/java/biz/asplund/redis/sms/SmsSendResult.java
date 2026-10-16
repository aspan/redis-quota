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
package biz.asplund.redis.sms;

import biz.asplund.redis.quota.QuotaStatus;

import java.util.List;

/// What became of a send: whether the quota allowed it, how many units it charged, and the recipients the gateway
/// did not accept, whose units were refunded.
///
/// The quota status is as it was after charging, before any refund.
///
/// @param outcome whether anything was sent, and if not, why
/// @param cost the units the send takes: one per part per recipient, whether or not it was charged
/// @param sent the recipients the gateway accepted
/// @param failed the recipients the gateway did not accept, whose units were refunded
/// @param quota the quota status after charging, or `null` when the sender id is not approved or the user has no
///              quota
public record SmsSendResult(Outcome outcome, int cost, List<String> sent, List<String> failed, QuotaStatus quota) {

    /// Whether a send went ahead, and if not, why.
    public enum Outcome {
        /// Charged and handed to the gateway; some recipients may have failed.
        SENT,
        /// The quota does not have room for the cost, so nothing was charged or sent.
        QUOTA_EXCEEDED,
        /// The user has no quota, so nothing was sent.
        NO_QUOTA,
        /// The sender id is not one of the user's approved ones, so nothing was charged or sent.
        SENDER_NOT_APPROVED
    }

    /// Copies the lists, so the result does not change with them.
    public SmsSendResult {
        sent = List.copyOf(sent);
        failed = List.copyOf(failed);
    }
}
