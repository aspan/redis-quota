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
import biz.asplund.redis.security.UserAccountService;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

// Sends SMS for a user, charged to their quota: one unit per part per recipient. The whole cost is charged before
// anything is sent, so a send either fits the quota or sends nothing, and the units of recipients the gateway does
// not accept are refunded. Like the API, it fails open when Redis cannot be reached. Only the sender ids an admin
// has approved for the user can be sent from
@Service
@EnableConfigurationProperties(SmsProperties.class)
public class SmsService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SmsService.class);

    public static final int MAX_RECIPIENTS = 100;
    public static final int MAX_PARTS = 10;

    // The number types a recipient may have; in some regions, such as the US, mobile and fixed-line numbers cannot
    // be told apart
    private static final Set<PhoneNumberUtil.PhoneNumberType> MOBILE_TYPES = Set.of(
            PhoneNumberUtil.PhoneNumberType.MOBILE, PhoneNumberUtil.PhoneNumberType.FIXED_LINE_OR_MOBILE);
    private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

    // A sender's number, with an optional +, after spaces, dashes, dots and parentheses are removed. Not checked
    // with libphonenumber, since a sender can also be a fixed-line number or a short code
    private static final Pattern PHONE_NUMBER = Pattern.compile("\\+?[0-9]{6,15}");
    private static final Pattern NUMBER_SEPARATORS = Pattern.compile("[\\s\\-.()]");
    // An alphanumeric sender id: at most 11 characters, the most the SMS header holds, of letters, digits, spaces
    // and . - _ &, with at least one letter, so it cannot be taken for a phone number
    public static final int MAX_ALPHANUMERIC_SENDER_ID_LENGTH = 11;
    private static final Pattern ALPHANUMERIC_SENDER_ID =
            Pattern.compile("(?=.*[A-Za-z])[A-Za-z0-9 ._&-]{1," + MAX_ALPHANUMERIC_SENDER_ID_LENGTH + "}");

    private final QuotaManager quotaManager;
    private final SmsSender smsSender;
    private final UserAccountService userAccountService;
    private final String defaultRegion;

    public SmsService(QuotaManager quotaManager, SmsSender smsSender, UserAccountService userAccountService,
            SmsProperties properties) {
        this.quotaManager = quotaManager;
        this.smsSender = smsSender;
        this.userAccountService = userAccountService;
        this.defaultRegion = properties.defaultRegion();
    }

    // The region a recipient's number without a country code is read in, such as SE
    public String defaultRegion() {
        return defaultRegion;
    }

    // The mobile number in E.164 as sent, such as +46701234567 for "+46 70-123 45 67", or for "070-123 45 67" when
    // the default region is SE; empty if it is not a valid mobile number, as libphonenumber judges it
    public Optional<String> normalizeRecipient(String recipient) {
        try {
            var number = PHONE_NUMBER_UTIL.parse(recipient, defaultRegion);
            if (PHONE_NUMBER_UTIL.isValidNumber(number)
                    && MOBILE_TYPES.contains(PHONE_NUMBER_UTIL.getNumberType(number))) {
                return Optional.of(PHONE_NUMBER_UTIL.format(number, PhoneNumberUtil.PhoneNumberFormat.E164));
            }
        } catch (NumberParseException e) {
            // Not a phone number at all
        }
        return Optional.empty();
    }

    // The sender id as sent: a phone number, with spaces, dashes, dots and parentheses removed, or an alphanumeric
    // id without spaces around it, such as "Lekab"; empty if it is neither
    public static Optional<String> normalizeSenderId(String senderId) {
        var trimmed = senderId.strip();
        if (ALPHANUMERIC_SENDER_ID.matcher(trimmed).matches()) {
            return Optional.of(trimmed);
        }
        var number = NUMBER_SEPARATORS.matcher(trimmed).replaceAll("");
        return PHONE_NUMBER.matcher(number).matches() ? Optional.of(number) : Optional.empty();
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
        if (!userAccountService.findSenderIds(username).contains(senderId)) {
            return new SmsSendResult(SmsSendResult.Outcome.SENDER_NOT_APPROVED, cost, List.of(), List.of(), null);
        }

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

    private void validate(String senderId, List<String> recipients, String text) {
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
                throw new IllegalArgumentException("Not a normalized mobile number: " + recipient);
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
