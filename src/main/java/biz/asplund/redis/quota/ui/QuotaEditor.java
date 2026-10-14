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
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.formlayout.FormLayout;
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
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.springframework.dao.DataAccessException;

import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.Consumer;

// Detail panel for creating or editing one quota definition and managing its usage
class QuotaEditor extends VerticalLayout {

    private final QuotaRow row;
    private final QuotaDefinitionRepository quotaDefinitionRepository;
    private final QuotaManager quotaManager;
    private final DateTimeFormatter dateTimeFormatter;
    private final Consumer<QuotaDefinition> onSaved;
    private final Consumer<QuotaDefinition> onDeleted;

    private final TextField idField = new TextField("Id");
    private final Map<QuotaPeriod, IntegerField> limitFields = new EnumMap<>(QuotaPeriod.class);

    QuotaEditor(QuotaRow row, QuotaDefinitionRepository quotaDefinitionRepository, QuotaManager quotaManager,
            DateTimeFormatter dateTimeFormatter, Consumer<QuotaDefinition> onSaved,
            Consumer<QuotaDefinition> onDeleted, Runnable onClose) {
        this.row = row;
        this.quotaDefinitionRepository = quotaDefinitionRepository;
        this.quotaManager = quotaManager;
        this.dateTimeFormatter = dateTimeFormatter;
        this.onSaved = onSaved;
        this.onDeleted = onDeleted;

        setWidthFull();

        var title = new H3(row == null ? "New quota" : row.id());
        var close = new Button(VaadinIcon.CLOSE.create(), _ -> onClose.run());
        close.setAriaLabel("Close");
        close.addThemeVariants(ButtonVariant.TERTIARY);
        var header = new HorizontalLayout(title, close);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.expand(title);
        add(header);

        if (row != null) {
            add(new Span("Last used: " + row.lastUsed().map(dateTimeFormatter::format).orElse("Never")));
        }

        add(createLimitsForm());

        if (row != null && !row.statuses().isEmpty()) {
            add(new H4("Current usage"));
            row.statuses().forEach((period, status) -> add(createUsageCard(period, status)));
        }
    }

    // Id of the edited definition, or null for a new one
    String quotaId() {
        return row == null ? null : row.id();
    }

    private Component createLimitsForm() {
        var form = new FormLayout();
        form.setAutoResponsive(true);

        if (row == null) {
            idField.setRequired(true);
            idField.setWidthFull();
            form.add(idField);
        }
        for (var period : QuotaPeriod.values()) {
            var field = new IntegerField(QuotaView.label(period) + " limit");
            field.setMin(0);
            field.setStepButtonsVisible(false);
            field.setPlaceholder("No limit");
            field.setClearButtonVisible(true);
            if (row != null) {
                field.setValue(row.limits().get(period));
            }
            limitFields.put(period, field);
            form.add(field);
        }

        var save = new Button("Save", _ -> save());
        save.addThemeVariants(ButtonVariant.PRIMARY);
        var buttons = new HorizontalLayout(save);
        if (row != null) {
            var delete = new Button("Delete", VaadinIcon.TRASH.create(), _ -> confirmDelete());
            delete.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.TERTIARY);
            buttons.add(delete);
        }

        var layout = new VerticalLayout(new H4("Limits"), form, buttons);
        layout.setPadding(false);
        return layout;
    }

    private Component createUsageCard(QuotaPeriod period, QuotaStatus status) {
        var card = new Card();
        card.setWidthFull();
        card.setTitle(QuotaView.label(period));
        card.setSubtitle(status.used() + " of " + status.limit() + " used, " + status.remaining() + " left");

        var progress = new ProgressBar(0, Math.max(1, status.limit()), Math.min(status.used(), status.limit()));
        if (status.used() >= status.limit()) {
            progress.addThemeVariants(ProgressBarVariant.ERROR);
        }
        card.add(progress, new Span("Resets " + dateTimeFormatter.format(status.resetAt())));

        var usageField = new IntegerField();
        usageField.setPlaceholder("Usage");
        usageField.setMin(0);
        usageField.setAriaLabel("New usage for " + QuotaView.label(period));
        usageField.setWidth("8rem");
        var set = new Button("Set", _ -> {
            if (usageField.getValue() == null || usageField.getValue() < 0) {
                usageField.setInvalid(true);
                usageField.setErrorMessage("Enter 0 or more");
                return;
            }
            modifyUsage(period, () -> quotaManager.setUsage(row.id(), period, usageField.getValue()));
        });
        var reset = new Button("Reset", VaadinIcon.ROTATE_LEFT.create(),
                _ -> modifyUsage(period, () -> quotaManager.resetQuota(row.id(), period)));
        reset.addThemeVariants(ButtonVariant.TERTIARY);

        var actions = new HorizontalLayout(usageField, set, reset);
        actions.setAlignItems(FlexComponent.Alignment.BASELINE);
        card.addToFooter(actions);
        return card;
    }

    private void modifyUsage(QuotaPeriod period, Runnable change) {
        try {
            change.run();
            notify(QuotaView.label(period) + " usage updated", NotificationVariant.SUCCESS);
            onSaved.accept(row.definition());
        } catch (DataAccessException e) {
            notify("Could not update usage: " + e.getMostSpecificCause().getMessage(), NotificationVariant.ERROR);
        }
    }

    private void save() {
        var id = row != null ? row.id() : idField.getValue().trim();
        if (id.isEmpty()) {
            idField.setInvalid(true);
            idField.setErrorMessage("Id is required");
            return;
        }

        var limits = new EnumMap<QuotaPeriod, Integer>(QuotaPeriod.class);
        limitFields.forEach((period, field) -> {
            if (field.getValue() != null) {
                limits.put(period, field.getValue());
            }
        });
        if (limitFields.values().stream().anyMatch(IntegerField::isInvalid)) {
            return;
        }

        try {
            if (row == null && quotaDefinitionRepository.existsById(id)) {
                idField.setInvalid(true);
                idField.setErrorMessage("A quota with this id already exists");
                return;
            }
            var definition = quotaDefinitionRepository.save(new QuotaDefinition(id, limits));
            notify("Quota " + id + " saved", NotificationVariant.SUCCESS);
            onSaved.accept(definition);
        } catch (DataAccessException e) {
            notify("Could not save quota: " + e.getMostSpecificCause().getMessage(), NotificationVariant.ERROR);
        }
    }

    private void confirmDelete() {
        var dialog = new ConfirmDialog("Delete quota " + row.id() + "?",
                "The limits are removed. Usage already counted expires with its period.",
                "Delete", _ -> delete());
        dialog.setConfirmButtonTheme("error primary");
        dialog.setCancelable(true);
        dialog.open();
    }

    private void delete() {
        try {
            quotaDefinitionRepository.deleteById(row.id());
            notify("Quota " + row.id() + " deleted", NotificationVariant.SUCCESS);
            onDeleted.accept(row.definition());
        } catch (DataAccessException e) {
            notify("Could not delete quota: " + e.getMostSpecificCause().getMessage(), NotificationVariant.ERROR);
        }
    }

    private static void notify(String text, NotificationVariant variant) {
        Notification.show(text).addThemeVariants(variant);
    }
}
