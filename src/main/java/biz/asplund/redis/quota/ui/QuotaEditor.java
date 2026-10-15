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
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
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
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.BeanValidationBinder;
import com.vaadin.flow.data.binder.ValidationException;
import org.springframework.dao.DataAccessException;

import java.time.format.DateTimeFormatter;
import java.util.function.Consumer;

// Detail panel for editing one quota definition, a limit for a single period, and managing its usage
class QuotaEditor extends VerticalLayout {

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
        add(new QuotaUsageCard(row.id(), row.definition().period(), row.status(), quotaManager, dateTimeFormatter,
                () -> onSaved.accept(row.definition())));
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
