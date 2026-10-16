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

import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaStatus;
import biz.asplund.redis.quota.QuotaStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

// Sends SMS for a user, charged to their quota: one unit per part per recipient. The whole cost is charged before
// anything is sent, so a send either fits the quota or sends nothing, and the units of recipients the gateway does
// not accept are refunded. Like the API, it fails open when Redis cannot be reached
@Service
public class SmsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmsService.class);

    public static final int MAX_RECIPIENTS = 100;
    public static final int MAX_PARTS = 10;

    // An international number with an optional +, after spaces, dashes, dots and parentheses are removed
    private static final Pattern PHONE_NUMBER = Pattern.compile("\\+?[0-9]{6,15}");
    private static final Pattern NUMBER_SEPARATORS = Pattern.compile("[\\s\\-.()]");
    // An alphanumeric sender id: at most 11 characters, the most the SMS header holds, of letters, digits, spaces
    // and . - _ &, with at least one letter, so it cannot be taken for a phone number
    public static final int MAX_ALPHANUMERIC_SENDER_ID_LENGTH = 11;
    private static final Pattern ALPHANUMERIC_SENDER_ID =
            Pattern.compile("(?=.*[A-Za-z])[A-Za-z0-9 ._&-]{1," + MAX_ALPHANUMERIC_SENDER_ID_LENGTH + "}");

    private final QuotaManager quotaManager;
    private final SmsSender smsSender;

    public SmsService(QuotaManager quotaManager, SmsSender smsSender) {
        this.quotaManager = quotaManager;
        this.smsSender = smsSender;
    }

    // The number as sent, such as +46701234567 for "+46 70-123 45 67", or empty if it is not a phone number
    public static Optional<String> normalizeRecipient(String recipient) {
        var number = NUMBER_SEPARATORS.matcher(recipient).replaceAll("");
        return PHONE_NUMBER.matcher(number).matches() ? Optional.of(number) : Optional.empty();
    }

    // The sender id as sent: a phone number, normalized as a recipient is, or an alphanumeric id without spaces
    // around it, such as "Lekab"; empty if it is neither
    public static Optional<String> normalizeSenderId(String senderId) {
        var trimmed = senderId.strip();
        if (ALPHANUMERIC_SENDER_ID.matcher(trimmed).matches()) {
            return Optional.of(trimmed);
        }
        return normalizeRecipient(trimmed);
    }

    // The units a text to the recipients uses up
    public static int cost(String text, int recipients) {
        return SmsParts.count(text) * recipients;
    }

    // The sender id and recipients must be normalized, the recipients distinct and at most MAX_RECIPIENTS, and the
    // text at most MAX_PARTS parts
    public SmsSendResult send(String username, String senderId, List<String> recipients, String text) {
        validate(senderId, recipients, text);
        var parts = SmsParts.count(text);
        var cost = parts * recipients.size();

        QuotaStatus quota;
        try {
            quota = quotaManager.checkAndIncrement(username, cost);
        } catch (IllegalArgumentException e) {
            return new SmsSendResult(SmsSendResult.Outcome.NO_QUOTA, cost, List.of(), List.of(), null);
        }
        if (!quota.allowed()) {
            return new SmsSendResult(SmsSendResult.Outcome.QUOTA_EXCEEDED, cost, List.of(), List.of(), quota);
        }

        var sent = new ArrayList<String>();
        var failed = new ArrayList<String>();
        for (var recipient : recipients) {
            try {
                smsSender.send(senderId, recipient, text);
                sent.add(recipient);
            } catch (RuntimeException e) {
                LOGGER.warn("Could not send SMS for {} to {}", username, recipient, e);
                failed.add(recipient);
            }
        }
        if (!failed.isEmpty()) {
            refund(username, parts * failed.size());
        }
        return new SmsSendResult(SmsSendResult.Outcome.SENT, cost, sent, failed, quota);
    }

    private static void validate(String senderId, List<String> recipients, String text) {
        if (!normalizeSenderId(senderId).equals(Optional.of(senderId))) {
            throw new IllegalArgumentException("Not a normalized sender id: " + senderId);
        }
        if (recipients.isEmpty() || recipients.size() > MAX_RECIPIENTS) {
            throw new IllegalArgumentException("Between 1 and " + MAX_RECIPIENTS + " recipients are needed, not "
                    + recipients.size());
        }
        if (recipients.stream().distinct().count() != recipients.size()) {
            throw new IllegalArgumentException("The recipients are not distinct: " + recipients);
        }
        for (var recipient : recipients) {
            if (!normalizeRecipient(recipient).equals(Optional.of(recipient))) {
                throw new IllegalArgumentException("Not a normalized phone number: " + recipient);
            }
        }
        if (text.isBlank()) {
            throw new IllegalArgumentException("The text is empty");
        }
        if (SmsParts.count(text) > MAX_PARTS) {
            throw new IllegalArgumentException("The text takes more than " + MAX_PARTS + " parts");
        }
    }

    // The messages are already sent, so a refund that fails is only logged
    private void refund(String username, int units) {
        try {
            quotaManager.adjustUsage(username, -units);
        } catch (RuntimeException e) {
            LOGGER.error("Could not refund {} units to {} for SMS that failed", units, username, e);
        }
    }
}
