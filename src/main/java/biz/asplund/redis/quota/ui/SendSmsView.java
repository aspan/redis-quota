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
package biz.asplund.redis.quota.ui;

import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.redis.security.UserAccountService;
import biz.asplund.redis.sms.SmsParts;
import biz.asplund.redis.sms.SmsSendResult;
import biz.asplund.redis.sms.SmsService;
import com.vaadin.flow.component.HasValidation;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

// Sends an SMS to one or more recipients, charged to the logged-in user's own quota: each part to each recipient
// uses one unit. It requires the USER or ADMIN role; Vaadin's view access check does not apply the
// role hierarchy in which ADMIN implies USER, so both are listed. Only a user with a quota and at least one sender
// id approved by an admin can send, and only from those sender ids
@Route("sms")
@PageTitle("sms.title")
@Menu(title = "sms.title", icon = "vaadin:envelope", order = 0.5)
@RolesAllowed({UserAccountService.USER_ROLE, UserAccountService.ADMIN_ROLE})
public class SendSmsView extends VerticalLayout implements LocaleChangeObserver {

    private static final Pattern RECIPIENT_SEPARATORS = Pattern.compile("[,;\\n]");

    private final UserAccountService userAccountService;
    private final QuotaManager quotaManager;
    private final SmsService smsService;
    private final ZoneId zone;
    private final String username;

    // Created in localeChange, so the page is built in the current language
    private QuotaPeriod period;
    private List<String> senderIds;
    private Div quotaHolder;
    private Select<String> senderField;
    private TextArea recipientsField;
    private TextArea messageField;
    private Span costSummary;

    public SendSmsView(UserAccountService userAccountService, QuotaManager quotaManager, SmsService smsService,
            Clock clock, AuthenticationContext authenticationContext) {
        this.userAccountService = userAccountService;
        this.quotaManager = quotaManager;
        this.smsService = smsService;
        this.zone = clock.getZone();
        this.username = authenticationContext.getPrincipalName().orElse("");
        setMaxWidth("40rem");
    }

    // Called when attached and whenever the language changes; input not yet sent is lost
    @Override
    public void localeChange(LocaleChangeEvent event) {
        removeAll();
        try {
            var quota = userAccountService.findQuota(username);
            if (quota.isEmpty()) {
                add(new Span(getTranslation("sms.noQuota")));
                return;
            }
            period = quota.get().period();
            senderIds = userAccountService.findSenderIds(username);
            if (senderIds.isEmpty()) {
                add(new Span(getTranslation("sms.noSenderIds")));
                return;
            }
        } catch (DataAccessException e) {
            add(new Span(getTranslation("sms.loadFailed", e.getMostSpecificCause().getMessage())));
            return;
        }

        senderField = new Select<>();
        senderField.setLabel(getTranslation("sms.sender"));
        senderField.setHelperText(getTranslation("sms.senderHint"));
        senderField.setItems(senderIds);
        if (senderIds.size() == 1) {
            senderField.setValue(senderIds.getFirst());
        }
        senderField.setWidthFull();

        recipientsField = new TextArea(getTranslation("sms.recipients"));
        recipientsField.setHelperText(getTranslation("sms.recipientsHint",
                Locale.of("", smsService.defaultRegion()).getDisplayCountry(getLocale()), SmsService.MAX_RECIPIENTS));
        recipientsField.setPlaceholder("+46701234567");
        recipientsField.setValueChangeMode(ValueChangeMode.LAZY);
        recipientsField.addValueChangeListener(_ -> updateCost());
        recipientsField.setWidthFull();

        messageField = new TextArea(getTranslation("sms.message"));
        messageField.setValueChangeMode(ValueChangeMode.EAGER);
        messageField.addValueChangeListener(_ -> updateCost());
        messageField.setMinHeight("8rem");
        messageField.setWidthFull();

        costSummary = new Span();
        var send = new Button(getTranslation("sms.send"), _ -> send());
        send.addThemeVariants(ButtonVariant.PRIMARY);

        quotaHolder = new Div();
        quotaHolder.setWidthFull();
        add(new Span(getTranslation("sms.hint")), quotaHolder, senderField, recipientsField,
                messageField, costSummary, send);
        showQuota();
        updateCost();
    }

    // Reads the usage again, read-only as on the home view
    private void showQuota() {
        var dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(getLocale()).withZone(zone);
        quotaHolder.removeAll();
        try {
            quotaHolder.add(new QuotaUsageCard(username, period, quotaManager.getStatus(username),
                    quotaManager.getLastUsed(username), quotaManager, dateTimeFormatter, null));
        } catch (IllegalArgumentException e) {
            // The copy in Redis is missing
            quotaHolder.add(new Span(getTranslation("sms.loadFailed", e.getMessage())));
        }
    }

    private void updateCost() {
        var text = messageField.getValue();
        var parts = SmsParts.count(text);
        messageField.setHelperText(getTranslation(SmsParts.isGsm(text) ? "sms.length" : "sms.lengthUnicode",
                text.length(), parts));
        var recipients = parseRecipients().valid().size();
        costSummary.setText(getTranslation("sms.cost", recipients, parts, SmsService.cost(text, recipients)));
    }

    private record Recipients(List<String> valid, List<String> invalid) {
    }

    // Distinct numbers in the order entered, and the entries that are not phone numbers
    private Recipients parseRecipients() {
        var valid = new LinkedHashSet<String>();
        var invalid = new ArrayList<String>();
        for (var entry : RECIPIENT_SEPARATORS.split(recipientsField.getValue())) {
            var trimmed = entry.strip();
            if (!trimmed.isEmpty()) {
                smsService.normalizeRecipient(trimmed).ifPresentOrElse(valid::add, () -> invalid.add(trimmed));
            }
        }
        return new Recipients(List.copyOf(valid), invalid);
    }

    private void send() {
        senderField.setInvalid(false);
        recipientsField.setInvalid(false);
        messageField.setInvalid(false);
        var senderId = senderField.getValue();
        var recipients = parseRecipients();
        var text = messageField.getValue();
        var valid = true;
        if (senderId == null) {
            valid = invalid(senderField, getTranslation("sms.senderRequired"));
        }
        if (!recipients.invalid().isEmpty()) {
            valid = invalid(recipientsField, getTranslation("sms.recipientsInvalid",
                    String.join(", ", recipients.invalid())));
        } else if (recipients.valid().isEmpty()) {
            valid = invalid(recipientsField, getTranslation("sms.recipientsRequired"));
        } else if (recipients.valid().size() > SmsService.MAX_RECIPIENTS) {
            valid = invalid(recipientsField, getTranslation("sms.recipientsTooMany", SmsService.MAX_RECIPIENTS));
        }
        if (text.isBlank()) {
            valid = invalid(messageField, getTranslation("sms.messageRequired"));
        } else if (SmsParts.count(text) > SmsService.MAX_PARTS) {
            valid = invalid(messageField, getTranslation("sms.messageTooLong", SmsService.MAX_PARTS));
        }
        if (!valid) {
            return;
        }

        var result = smsService.send(username, senderId, recipients.valid(), text);
        switch (result.outcome()) {
            case SENT -> sent(result);
            case QUOTA_EXCEEDED -> notify(getTranslation("sms.quotaExceeded", result.cost(),
                    result.quota().remaining()), NotificationVariant.ERROR);
            case NO_QUOTA -> notify(getTranslation("sms.noQuota"), NotificationVariant.ERROR);
            // An admin took the sender id away after the page was loaded
            case SENDER_NOT_APPROVED -> notify(getTranslation("sms.senderNotApproved", senderId),
                    NotificationVariant.ERROR);
        }
        showQuota();
    }

    private void sent(SmsSendResult result) {
        if (result.failed().isEmpty()) {
            notify(getTranslation("sms.sent", result.sent().size(), result.cost()), NotificationVariant.SUCCESS);
            messageField.clear();
            return;
        }
        // Keeps the recipients that failed, so they can be sent to again
        recipientsField.setValue(String.join("\n", result.failed()));
        notify(getTranslation("sms.sendFailed", result.sent().size(), String.join(", ", result.failed())),
                NotificationVariant.ERROR);
    }

    private static boolean invalid(HasValidation field, String message) {
        field.setInvalid(true);
        field.setErrorMessage(message);
        return false;
    }

    private static void notify(String text, NotificationVariant variant) {
        Notification.show(text, 5000, Notification.Position.TOP_CENTER).addThemeVariants(variant);
    }
}
