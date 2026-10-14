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
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.masterdetaillayout.MasterDetailLayout;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.StreamSupport;

@Route("")
@PageTitle("Quotas")
@Menu(title = "Quotas", icon = "vaadin:dashboard", order = 0)
@RolesAllowed("ADMIN")
public class QuotaView extends VerticalLayout implements BeforeEnterObserver {

    private final QuotaDefinitionRepository quotaDefinitionRepository;
    private final QuotaManager quotaManager;
    private final DateTimeFormatter dateTimeFormatter;

    private final Grid<QuotaRow> grid = new Grid<>();
    private final TextField filter = new TextField();
    private final MasterDetailLayout masterDetail = new MasterDetailLayout();

    private List<QuotaRow> rows = List.of();

    public QuotaView(QuotaDefinitionRepository quotaDefinitionRepository, QuotaManager quotaManager, Clock clock) {
        this.quotaDefinitionRepository = quotaDefinitionRepository;
        this.quotaManager = quotaManager;
        this.dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withZone(clock.getZone());

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        masterDetail.setMaster(createMaster());
        masterDetail.setMasterSize("40rem", true);
        masterDetail.setDetailSize("28rem");
        masterDetail.addDetailEscapePressListener(_ -> grid.deselectAll());
        masterDetail.addBackdropClickListener(_ -> grid.deselectAll());
        masterDetail.setSizeFull();
        add(masterDetail);
    }

    // Reload on every navigation, including to this view when it is already shown
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        refresh();
    }

    private Component createMaster() {
        filter.setPlaceholder("Filter by id");
        filter.setWidth("20rem");
        filter.setPrefixComponent(VaadinIcon.SEARCH.create());
        filter.setClearButtonVisible(true);
        filter.setValueChangeMode(ValueChangeMode.LAZY);
        filter.addValueChangeListener(_ -> applyFilter());

        var refresh = new Button(VaadinIcon.REFRESH.create(), _ -> refresh());
        refresh.setTooltipText("Refresh");
        refresh.setAriaLabel("Refresh");
        refresh.addThemeVariants(ButtonVariant.TERTIARY);

        var add = new Button("New quota", VaadinIcon.PLUS.create(), _ -> {
            grid.deselectAll();
            showEditor(null);
        });
        add.addThemeVariants(ButtonVariant.PRIMARY);

        var toolbar = new HorizontalLayout(filter, refresh, add);
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
        add.getStyle().set("margin-inline-start", "auto");

        grid.addColumn(QuotaRow::id).setHeader("Id").setSortable(true)
                .setComparator(Comparator.comparing(QuotaRow::id)).setFlexGrow(2).setFrozen(true);
        for (var period : QuotaPeriod.values()) {
            grid.addColumn(new ComponentRenderer<>(row -> usageBadge(row.statuses().get(period))))
                    .setHeader(label(period)).setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        }
        grid.addColumn(row -> row.lastUsed().map(dateTimeFormatter::format).orElse("Never"))
                .setHeader("Last used").setAutoWidth(true);
        grid.setEmptyStateText("No quota definitions");
        grid.asSingleSelect().addValueChangeListener(event -> {
            if (event.getValue() != null) {
                showEditor(event.getValue());
            } else if (event.isFromClient()) {
                masterDetail.setDetail(null);
            }
        });
        grid.setSizeFull();

        var master = new VerticalLayout(toolbar, grid);
        master.setSizeFull();
        master.setFlexGrow(1, grid);
        return master;
    }

    private Component usageBadge(QuotaStatus status) {
        if (status == null) {
            return new Span("—");
        }
        var badge = new Badge(status.used() + " / " + status.limit());
        if (status.used() >= status.limit()) {
            badge.addThemeVariants(BadgeVariant.ERROR);
        } else if (status.used() >= status.limit() * 0.8) {
            badge.addThemeVariants(BadgeVariant.WARNING);
        } else {
            badge.addThemeVariants(BadgeVariant.SUCCESS);
        }
        return badge;
    }

    private void showEditor(QuotaRow row) {
        masterDetail.setDetail(new QuotaEditor(row, quotaDefinitionRepository, quotaManager, dateTimeFormatter,
                this::onSaved, this::onDeleted, () -> grid.deselectAll()));
    }

    private void onSaved(QuotaDefinition definition) {
        refresh();
        rows.stream().filter(row -> row.id().equals(definition.id())).findFirst().ifPresent(row -> {
            grid.select(row);
            showEditor(row);
        });
    }

    private void onDeleted(QuotaDefinition definition) {
        grid.deselectAll();
        masterDetail.setDetail(null);
        refresh();
    }

    void refresh() {
        try {
            rows = StreamSupport.stream(quotaDefinitionRepository.findAll().spliterator(), false)
                    .filter(Objects::nonNull)
                    .map(definition -> QuotaRow.load(definition, quotaManager))
                    .sorted(Comparator.comparing(QuotaRow::id))
                    .toList();
        } catch (DataAccessException e) {
            rows = List.of();
            var notification = Notification.show("Could not load quotas: " + e.getMostSpecificCause().getMessage());
            notification.addThemeVariants(NotificationVariant.ERROR);
        }
        applyFilter();

        // Keep the open editor showing current usage
        if (masterDetail.getDetail() instanceof QuotaEditor editor && editor.quotaId() != null) {
            rows.stream().filter(row -> row.id().equals(editor.quotaId())).findFirst().ifPresent(row -> {
                grid.select(row);
                showEditor(row);
            });
        }
    }

    private void applyFilter() {
        var term = filter.getValue().trim().toLowerCase(Locale.ROOT);
        grid.setItems(term.isEmpty() ? rows
                : rows.stream().filter(row -> row.id().toLowerCase(Locale.ROOT).contains(term)).toList())
                .setIdentifierProvider(QuotaRow::id);
    }

    static String label(QuotaPeriod period) {
        var name = period.name().toLowerCase(Locale.ROOT);
        return Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
