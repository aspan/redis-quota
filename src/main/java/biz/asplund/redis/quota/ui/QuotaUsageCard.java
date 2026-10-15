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
import biz.asplund.redis.quota.QuotaStatus;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.progressbar.ProgressBarVariant;
import com.vaadin.flow.component.textfield.IntegerField;
import org.springframework.dao.DataAccessException;

import java.time.format.DateTimeFormatter;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

// The usage of one quota id in its current period, with actions to set, adjust or reset it and to run a request
// as a client would. Shown in the quota editor and, for a user with a quota, in the user editor. After a change
// the card calls onChanged, which rebuilds it with the new status. Without onChanged, as on the home view, the card
// only shows the usage
class QuotaUsageCard extends Card {

    private static final String PROGRESS_COLOR = "--vaadin-progress-bar-value-background";

    private final String id;
    private final QuotaPeriod period;
    private final QuotaManager quotaManager;
    private final Runnable onChanged;

    QuotaUsageCard(String id, QuotaPeriod period, QuotaStatus status, Optional<Instant> lastUsed,
            QuotaManager quotaManager, DateTimeFormatter dateTimeFormatter, Runnable onChanged) {
        this.id = id;
        this.period = period;
        this.quotaManager = quotaManager;
        this.onChanged = onChanged;

        setWidthFull();
        setTitle(QuotaView.label(period));
        setSubtitle(getTranslation("usage.summary", status.used(), status.limit(), status.remaining()));

        var progress = new ProgressBar(0, Math.max(1, status.limit()), Math.min(status.used(), status.limit()));
        colorProgress(progress, status);
        add(progress, new Div(getTranslation("usage.resets", dateTimeFormatter.format(status.resetAt()))),
                new Div(getTranslation("usage.lastUsed",
                        lastUsed.map(dateTimeFormatter::format).orElse(getTranslation("quotas.never")))));
        if (onChanged == null) {
            return;
        }

        var usageField = new IntegerField();
        usageField.setPlaceholder(getTranslation("usage.placeholder"));
        usageField.setMin(0);
        usageField.setAriaLabel(getTranslation("usage.newUsage", QuotaView.label(period)));
        var set = new Button(getTranslation("usage.set"), _ -> {
            if (usageField.getValue() == null || usageField.getValue() < 0) {
                usageField.setInvalid(true);
                usageField.setErrorMessage(getTranslation("usage.invalid"));
                return;
            }
            modifyUsage(() -> quotaManager.setUsage(id, usageField.getValue()));
        });
        var reset = new Button(getTranslation("usage.reset"), VaadinIcon.ROTATE_LEFT.create(),
                _ -> modifyUsage(() -> quotaManager.resetQuota(id)));
        reset.addThemeVariants(ButtonVariant.TERTIARY);

        // Runs a request against the quota as a client would, which also records the last use
        var amountField = new IntegerField();
        amountField.setValue(1);
        amountField.setMin(1);
        amountField.setStepButtonsVisible(true);
        amountField.setAriaLabel(getTranslation("usage.amount"));
        var checkAndIncrement = new Button(getTranslation("usage.checkAndIncrement"), VaadinIcon.PLAY.create(),
                _ -> checkAndIncrement(amountField));

        // Adds a signed amount to the usage, as a client refunding or charging extra would; usage stays at 0 or more
        var deltaField = new IntegerField();
        deltaField.setStepButtonsVisible(true);
        deltaField.setPlaceholder(getTranslation("usage.deltaPlaceholder"));
        deltaField.setAriaLabel(getTranslation("usage.delta", QuotaView.label(period)));
        var adjust = new Button(getTranslation("usage.adjust"), VaadinIcon.PLUS_MINUS.create(), _ -> {
            if (deltaField.getValue() == null || deltaField.getValue() == 0) {
                deltaField.setInvalid(true);
                deltaField.setErrorMessage(getTranslation("usage.deltaInvalid"));
                return;
            }
            modifyUsage(() -> quotaManager.adjustUsage(id, deltaField.getValue()));
        });

        // A grid of one row per action, so the fields line up and fill the card beside a shared button column
        var footer = new Div(usageField, set, reset, deltaField, adjust, amountField, checkAndIncrement);
        footer.setWidthFull();
        footer.getStyle()
                .set("display", "grid")
                .set("grid-template-columns", "1fr auto auto")
                .set("align-items", "baseline")
                .set("gap", "var(--vaadin-gap-s)");
        for (var field : List.of(usageField, deltaField, amountField)) {
            field.setWidthFull();
        }
        // The single buttons span the column of Set and Reset
        for (var button : List.of(adjust, checkAndIncrement)) {
            button.setWidthFull();
            button.getStyle().set("grid-column", "span 2");
        }
        addToFooter(footer);
    }

    // Matches the usage badge in the list. Progress Bar has no warning variant, and the docs list its success
    // and error variants for Lumo only, so the Aura palette color is set as well
    private static void colorProgress(ProgressBar progress, QuotaStatus status) {
        if (QuotaView.isUsedUp(status)) {
            progress.addThemeVariants(ProgressBarVariant.ERROR);
            progress.getStyle().set(PROGRESS_COLOR, "var(--aura-red)");
        } else if (QuotaView.isRunningLow(status)) {
            progress.getStyle().set(PROGRESS_COLOR, "var(--aura-orange)");
        } else {
            progress.addThemeVariants(ProgressBarVariant.SUCCESS);
            progress.getStyle().set(PROGRESS_COLOR, "var(--aura-green)");
        }
    }

    private void checkAndIncrement(IntegerField amountField) {
        var amount = amountField.getValue();
        if (amount == null || amount < 1) {
            amountField.setInvalid(true);
            amountField.setErrorMessage(getTranslation("usage.amountInvalid"));
            return;
        }
        try {
            var status = quotaManager.checkAndIncrement(id, amount);
            if (status.allowed()) {
                notify(getTranslation("usage.allowed", status.used(), status.limit(), status.remaining()),
                        NotificationVariant.SUCCESS);
            } else {
                notify(getTranslation("usage.rejected", amount, status.used(), status.limit(), status.remaining()),
                        NotificationVariant.WARNING);
            }
            onChanged.run();
        } catch (IllegalArgumentException | DataAccessException e) {
            // The definition may have been deleted elsewhere
            var cause = e instanceof DataAccessException dataAccess ? dataAccess.getMostSpecificCause() : e;
            notify(getTranslation("usage.checkFailed", cause.getMessage()), NotificationVariant.ERROR);
        }
    }

    private void modifyUsage(Runnable change) {
        try {
            change.run();
            notify(getTranslation("usage.updated", QuotaView.label(period)), NotificationVariant.SUCCESS);
            onChanged.run();
        } catch (IllegalArgumentException | DataAccessException e) {
            // The definition may have been deleted elsewhere
            var cause = e instanceof DataAccessException dataAccess ? dataAccess.getMostSpecificCause() : e;
            notify(getTranslation("usage.updateFailed", cause.getMessage()), NotificationVariant.ERROR);
        }
    }

    private static void notify(String text, NotificationVariant variant) {
        Notification.show(text).addThemeVariants(variant);
    }
}
