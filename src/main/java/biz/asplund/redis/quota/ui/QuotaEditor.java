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
import biz.asplund.redis.security.QuotaHistoryService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;

import java.time.format.DateTimeFormatter;

// Detail panel for one user's quota: its period and limit, read-only as they are set on the user in the users view,
// its usage in the current period, which can be changed here, and its usage history
class QuotaEditor extends VerticalLayout {

    private final QuotaRow row;

    QuotaEditor(QuotaRow row, QuotaManager quotaManager, QuotaHistoryService quotaHistoryService,
            DateTimeFormatter dateTimeFormatter, Runnable onUsageChanged, Runnable onClose) {
        this.row = row;

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
        add(new H4(getTranslation("editor.quota")), createQuotaForm());
        add(new H4(getTranslation("editor.currentUsage")));
        add(new QuotaUsageCard(row.id(), row.definition().period(), row.status(), row.lastUsed(), quotaManager,
                dateTimeFormatter, onUsageChanged));
        // Every quota is a user's, under the username
        add(new UsageHistorySection(quotaHistoryService, row.id(), dateTimeFormatter));
    }

    // Id of the shown definition
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

        var periodField = new Select<QuotaPeriod>();
        periodField.setLabel(getTranslation("editor.period"));
        periodField.setItems(QuotaPeriod.values());
        periodField.setItemLabelGenerator(QuotaView::label);
        periodField.setValue(row.definition().period());
        periodField.setReadOnly(true);

        var limitField = new IntegerField();
        limitField.setLabel(getTranslation("editor.limit"));
        limitField.setValue(row.definition().limit());
        limitField.setReadOnly(true);
        limitField.setHelperText(getTranslation("editor.userQuotaHint"));

        form.add(periodField, limitField);
        return form;
    }
}
