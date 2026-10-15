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

import biz.asplund.redis.quota.QuotaDefinition;
import biz.asplund.redis.quota.QuotaDefinitionRepository;
import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.redis.quota.QuotaStatus;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.progressbar.ProgressBarVariant;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.BeanValidationBinder;
import com.vaadin.flow.data.binder.ValidationException;
import org.springframework.dao.DataAccessException;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Consumer;

// Detail panel for editing one quota definition, a limit for a single period, and managing its usage
class QuotaEditor extends VerticalLayout {

    private static final String PROGRESS_COLOR = "--vaadin-progress-bar-value-background";

    private final QuotaRow row;
    // The definition is a user's quota, copied from PostgreSQL, so its period and limit are changed there
    private final boolean userQuota;
    private final QuotaDefinitionRepository quotaDefinitionRepository;
    private final QuotaManager quotaManager;
    private final DateTimeFormatter dateTimeFormatter;
    private final Consumer<QuotaDefinition> onSaved;

    private final TextField idField = new TextField();
    private final Select<QuotaPeriod> periodField = new Select<>();
    private final IntegerField limitField = new IntegerField();
    // Validates with the constraints on QuotaDefinition, and creates the record from the fields
    private final BeanValidationBinder<QuotaDefinition> binder = new BeanValidationBinder<>(QuotaDefinition.class);

    QuotaEditor(QuotaRow row, boolean userQuota, QuotaDefinitionRepository quotaDefinitionRepository,
            QuotaManager quotaManager, DateTimeFormatter dateTimeFormatter, Consumer<QuotaDefinition> onSaved,
            Runnable onClose) {
        this.row = row;
        this.userQuota = userQuota;
        this.quotaDefinitionRepository = quotaDefinitionRepository;
        this.quotaManager = quotaManager;
        this.dateTimeFormatter = dateTimeFormatter;
        this.onSaved = onSaved;

        setWidthFull();

        var title = new H3(row.id());
        var close = new Button(VaadinIcon.CLOSE.create(), _ -> onClose.run());
        close.setAriaLabel(getTranslation("editor.close"));
        close.addThemeVariants(ButtonVariant.TERTIARY);
        var header = new HorizontalLayout(title, close);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.expand(title);
        add(header);
        add(new Span(getTranslation("editor.lastUsed",
                row.lastUsed().map(dateTimeFormatter::format).orElse(getTranslation("quotas.never")))));
        add(createQuotaForm());
        add(new H4(getTranslation("editor.currentUsage")));
        add(createUsageCard(row.definition().period(), row.status()));
    }

    // Id of the edited definition
    String quotaId() {
        return row.id();
    }

    private Component createQuotaForm() {
        // One column across the editor, with each field filling it
        var form = new FormLayout();
        form.setAutoResponsive(true);
        form.setMaxColumns(1);
        form.setExpandColumns(true);
        form.setExpandFields(true);
        form.setWidthFull();

        periodField.setLabel(getTranslation("editor.period"));
        periodField.setItems(QuotaPeriod.values());
        periodField.setItemLabelGenerator(QuotaView::label);

        limitField.setLabel(getTranslation("editor.limit"));
        // No min: the field's own range check would show its own, empty message instead of @PositiveOrZero's
        limitField.setStepButtonsVisible(false);

        // The id is not edited, but writeRecord needs a binding for every component, so a field not shown holds it
        binder.forField(idField).bind("id");
        binder.forField(periodField).bind("period");
        // The limit is an int, so an empty field is refused before it reaches the record
        binder.forField(limitField).asRequired(getTranslation("editor.limitRequired")).bind("limit");

        binder.readRecord(row.definition());
        // Usage is counted per period, so a new period starts from zero
        periodField.setHelperText(getTranslation("editor.periodChangeHint"));
        form.add(periodField, limitField);

        if (userQuota) {
            periodField.setReadOnly(true);
            periodField.setHelperText(null);
            limitField.setReadOnly(true);
            limitField.setHelperText(getTranslation("editor.userQuotaHint"));
        }

        var save = new Button(getTranslation("editor.save"), _ -> save());
        save.addThemeVariants(ButtonVariant.PRIMARY);
        var buttons = new HorizontalLayout(save);
        // A user's quota is saved with the user. Quotas belong to users, so none is deleted here: deleting the
        // user or removing their quota deletes it
        buttons.setVisible(!userQuota);

        var layout = new VerticalLayout(new H4(getTranslation("editor.quota")), form, buttons);
        layout.setWidthFull();
        layout.setPadding(false);
        return layout;
    }

    private Component createUsageCard(QuotaPeriod period, QuotaStatus status) {
        var card = new Card();
        card.setWidthFull();
        card.setTitle(QuotaView.label(period));
        card.setSubtitle(getTranslation("usage.summary", status.used(), status.limit(), status.remaining()));

        var progress = new ProgressBar(0, Math.max(1, status.limit()), Math.min(status.used(), status.limit()));
        colorProgress(progress, status);
        card.add(progress, new Span(getTranslation("usage.resets", dateTimeFormatter.format(status.resetAt()))));

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
            modifyUsage(period, () -> quotaManager.setUsage(row.id(), usageField.getValue()));
        });
        var reset = new Button(getTranslation("usage.reset"), VaadinIcon.ROTATE_LEFT.create(),
                _ -> modifyUsage(period, () -> quotaManager.resetQuota(row.id())));
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
            modifyUsage(period, () -> quotaManager.adjustUsage(row.id(), deltaField.getValue()));
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
        card.addToFooter(footer);
        return card;
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
            var status = quotaManager.checkAndIncrement(row.id(), amount);
            if (status.allowed()) {
                notify(getTranslation("usage.allowed", status.used(), status.limit(), status.remaining()),
                        NotificationVariant.SUCCESS);
            } else {
                notify(getTranslation("usage.rejected", amount, status.used(), status.limit(), status.remaining()),
                        NotificationVariant.WARNING);
            }
            onSaved.accept(row.definition());
        } catch (IllegalArgumentException | DataAccessException e) {
            // The definition may have been deleted elsewhere
            var cause = e instanceof DataAccessException dataAccess ? dataAccess.getMostSpecificCause() : e;
            notify(getTranslation("usage.checkFailed", cause.getMessage()), NotificationVariant.ERROR);
        }
    }

    private void modifyUsage(QuotaPeriod period, Runnable change) {
        try {
            change.run();
            notify(getTranslation("usage.updated", QuotaView.label(period)), NotificationVariant.SUCCESS);
            onSaved.accept(row.definition());
        } catch (IllegalArgumentException | DataAccessException e) {
            // The definition may have been deleted elsewhere
            var cause = e instanceof DataAccessException dataAccess ? dataAccess.getMostSpecificCause() : e;
            notify(getTranslation("usage.updateFailed", cause.getMessage()), NotificationVariant.ERROR);
        }
    }

    private void save() {
        QuotaDefinition definition;
        try {
            definition = binder.writeRecord();
        } catch (ValidationException e) {
            // The binder shows the errors on the fields
            return;
        }

        try {
            var saved = quotaDefinitionRepository.save(definition);
            notify(getTranslation("editor.saved", saved.id()), NotificationVariant.SUCCESS);
            onSaved.accept(saved);
        } catch (DataAccessException e) {
            notify(getTranslation("editor.saveFailed", e.getMostSpecificCause().getMessage()), NotificationVariant.ERROR);
        }
    }

    private static void notify(String text, NotificationVariant variant) {
        Notification.show(text).addThemeVariants(variant);
    }
}
