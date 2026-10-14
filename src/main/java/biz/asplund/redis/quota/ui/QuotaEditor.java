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
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import org.springframework.dao.DataAccessException;

import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

// Detail panel for creating or editing one quota definition, a limit for a single period, and managing its usage
class QuotaEditor extends VerticalLayout {

    private final QuotaRow row;
    private final QuotaDefinitionRepository quotaDefinitionRepository;
    private final QuotaManager quotaManager;
    private final DateTimeFormatter dateTimeFormatter;
    private final Consumer<QuotaDefinition> onSaved;
    private final Consumer<QuotaDefinition> onDeleted;

    private final TextField idField = new TextField();
    private final Select<QuotaPeriod> periodField = new Select<>();
    private final IntegerField limitField = new IntegerField();

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

        var title = new H3(row == null ? getTranslation("editor.newTitle") : row.id());
        var close = new Button(VaadinIcon.CLOSE.create(), _ -> onClose.run());
        close.setAriaLabel(getTranslation("editor.close"));
        close.addThemeVariants(ButtonVariant.TERTIARY);
        var header = new HorizontalLayout(title, close);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.expand(title);
        add(header);

        if (row != null) {
            add(new Span(getTranslation("editor.lastUsed",
                    row.lastUsed().map(dateTimeFormatter::format).orElse(getTranslation("quotas.never")))));
        }

        add(createQuotaForm());

        if (row != null) {
            add(new H4(getTranslation("editor.currentUsage")));
            add(createUsageCard(row.definition().period(), row.status()));
        }
    }

    // Id of the edited definition, or null for a new one
    String quotaId() {
        return row == null ? null : row.id();
    }

    private Component createQuotaForm() {
        var form = new FormLayout();
        form.setAutoResponsive(true);

        if (row == null) {
            idField.setLabel(getTranslation("editor.id"));
            idField.setRequired(true);
            idField.setWidthFull();
            form.add(idField);
        }

        periodField.setLabel(getTranslation("editor.period"));
        periodField.setItems(QuotaPeriod.values());
        periodField.setItemLabelGenerator(QuotaView::label);
        periodField.setRequiredIndicatorVisible(true);

        limitField.setLabel(getTranslation("editor.limit"));
        limitField.setMin(0);
        limitField.setStepButtonsVisible(false);
        limitField.setRequiredIndicatorVisible(true);

        if (row != null) {
            periodField.setValue(row.definition().period());
            // Usage is counted per period, so a new period starts from zero
            periodField.setHelperText(getTranslation("editor.periodChangeHint"));
            limitField.setValue(row.definition().limit());
        }
        form.add(periodField, limitField);

        var save = new Button(getTranslation("editor.save"), _ -> save());
        save.addThemeVariants(ButtonVariant.PRIMARY);
        var buttons = new HorizontalLayout(save);
        if (row != null) {
            var delete = new Button(getTranslation("editor.delete"), VaadinIcon.TRASH.create(), _ -> confirmDelete());
            delete.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.TERTIARY);
            buttons.add(delete);
        }

        var layout = new VerticalLayout(new H4(getTranslation("editor.quota")), form, buttons);
        layout.setPadding(false);
        return layout;
    }

    private Component createUsageCard(QuotaPeriod period, QuotaStatus status) {
        var card = new Card();
        card.setWidthFull();
        card.setTitle(QuotaView.label(period));
        card.setSubtitle(getTranslation("usage.summary", status.used(), status.limit(), status.remaining()));

        var progress = new ProgressBar(0, Math.max(1, status.limit()), Math.min(status.used(), status.limit()));
        if (status.used() >= status.limit()) {
            progress.addThemeVariants(ProgressBarVariant.ERROR);
        }
        card.add(progress, new Span(getTranslation("usage.resets", dateTimeFormatter.format(status.resetAt()))));

        var usageField = new IntegerField();
        usageField.setPlaceholder(getTranslation("usage.placeholder"));
        usageField.setMin(0);
        usageField.setAriaLabel(getTranslation("usage.newUsage", QuotaView.label(period)));
        usageField.setWidth("8rem");
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

        var actions = new HorizontalLayout(usageField, set, reset);
        actions.setAlignItems(FlexComponent.Alignment.BASELINE);
        card.addToFooter(actions);
        return card;
    }

    private void modifyUsage(QuotaPeriod period, Runnable change) {
        try {
            change.run();
            notify(getTranslation("usage.updated", QuotaView.label(period)), NotificationVariant.SUCCESS);
            onSaved.accept(row.definition());
        } catch (DataAccessException e) {
            notify(getTranslation("usage.updateFailed", e.getMostSpecificCause().getMessage()), NotificationVariant.ERROR);
        }
    }

    private void save() {
        var id = row != null ? row.id() : idField.getValue().trim();
        if (id.isEmpty()) {
            idField.setInvalid(true);
            idField.setErrorMessage(getTranslation("editor.idRequired"));
            return;
        }

        var valid = true;
        if (periodField.getValue() == null) {
            periodField.setInvalid(true);
            periodField.setErrorMessage(getTranslation("editor.periodRequired"));
            valid = false;
        }
        if (limitField.getValue() == null || limitField.getValue() < 0) {
            limitField.setInvalid(true);
            limitField.setErrorMessage(getTranslation("editor.limitRequired"));
            valid = false;
        }
        if (!valid) {
            return;
        }

        try {
            if (row == null && quotaDefinitionRepository.existsById(id)) {
                idField.setInvalid(true);
                idField.setErrorMessage(getTranslation("editor.idExists"));
                return;
            }
            var definition = quotaDefinitionRepository.save(
                    new QuotaDefinition(id, periodField.getValue(), limitField.getValue()));
            notify(getTranslation("editor.saved", id), NotificationVariant.SUCCESS);
            onSaved.accept(definition);
        } catch (DataAccessException e) {
            notify(getTranslation("editor.saveFailed", e.getMostSpecificCause().getMessage()), NotificationVariant.ERROR);
        }
    }

    private void confirmDelete() {
        var dialog = new ConfirmDialog(getTranslation("editor.deleteTitle", row.id()),
                getTranslation("editor.deleteText"), getTranslation("editor.delete"), _ -> delete());
        dialog.setCancelText(getTranslation("editor.cancel"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.setCancelable(true);
        dialog.open();
    }

    private void delete() {
        try {
            quotaDefinitionRepository.deleteById(row.id());
            notify(getTranslation("editor.deleted", row.id()), NotificationVariant.SUCCESS);
            onDeleted.accept(row.definition());
        } catch (DataAccessException e) {
            notify(getTranslation("editor.deleteFailed", e.getMostSpecificCause().getMessage()), NotificationVariant.ERROR);
        }
    }

    private static void notify(String text, NotificationVariant variant) {
        Notification.show(text).addThemeVariants(variant);
    }
}
