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
import biz.asplund.redis.security.UserAccountService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
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
import com.vaadin.flow.i18n.I18NProvider;
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.StreamSupport;

@Route("")
@PageTitle("quotas.title")
@Menu(title = "quotas.title", icon = "vaadin:dashboard", order = 0)
@RolesAllowed("ADMIN")
public class QuotaView extends VerticalLayout implements BeforeEnterObserver, LocaleChangeObserver {

    private final QuotaDefinitionRepository quotaDefinitionRepository;
    private final QuotaManager quotaManager;
    private final UserAccountService userAccountService;
    private final ZoneId zone;
    private DateTimeFormatter dateTimeFormatter;

    private final Grid<QuotaRow> grid = new Grid<>();
    private final TextField filter = new TextField();
    private final Button refreshButton = new Button(VaadinIcon.REFRESH.create());
    private final MasterDetailLayout masterDetail = new MasterDetailLayout();

    private Grid.Column<QuotaRow> idColumn;
    private Grid.Column<QuotaRow> periodColumn;
    private Grid.Column<QuotaRow> usageColumn;
    private Grid.Column<QuotaRow> resetsColumn;
    private Grid.Column<QuotaRow> lastUsedColumn;

    private List<QuotaRow> rows = List.of();
    // Ids of the definitions that are users' quotas, whose period and limit are set in the users view
    private Set<String> userQuotaIds = Set.of();

    public QuotaView(QuotaDefinitionRepository quotaDefinitionRepository, QuotaManager quotaManager,
            UserAccountService userAccountService, Clock clock) {
        this.quotaDefinitionRepository = quotaDefinitionRepository;
        this.quotaManager = quotaManager;
        this.userAccountService = userAccountService;
        this.zone = clock.getZone();
        this.dateTimeFormatter = createDateTimeFormatter(getLocale());

        setSizeFull();
        setPadding(false);
        setSpacing(false);

        masterDetail.setMaster(createMaster());
        masterDetail.setMasterSize("40rem", true);
        masterDetail.setDetailSize("28rem");
        masterDetail.addDetailEscapePressListener(_ -> closeEditor());
        masterDetail.addBackdropClickListener(_ -> closeEditor());
        masterDetail.setSizeFull();
        add(masterDetail);
    }

    // Called when attached and whenever the language changes
    @Override
    public void localeChange(LocaleChangeEvent event) {
        dateTimeFormatter = createDateTimeFormatter(event.getLocale());

        filter.setPlaceholder(getTranslation("quotas.filter"));
        refreshButton.setTooltipText(getTranslation("quotas.refresh"));
        refreshButton.setAriaLabel(getTranslation("quotas.refresh"));

        idColumn.setHeader(getTranslation("quotas.column.id"));
        periodColumn.setHeader(getTranslation("quotas.column.period"));
        usageColumn.setHeader(getTranslation("quotas.column.usage"));
        resetsColumn.setHeader(getTranslation("quotas.column.resets"));
        lastUsedColumn.setHeader(getTranslation("quotas.column.lastUsed"));
        grid.setEmptyStateText(getTranslation("quotas.empty"));
        grid.getDataProvider().refreshAll();

        // Rebuild an open editor in the new language; unsaved input in it is lost
        if (masterDetail.getDetail() instanceof QuotaEditor editor) {
            rows.stream().filter(row -> row.id().equals(editor.quotaId())).findFirst()
                    .ifPresentOrElse(this::showEditor, this::closeEditor);
        }
    }

    private DateTimeFormatter createDateTimeFormatter(Locale locale) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone);
    }

    // Reload on every navigation, including to this view when it is already shown
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        refresh();
    }

    private Component createMaster() {
        filter.setWidth("20rem");
        filter.setPrefixComponent(VaadinIcon.SEARCH.create());
        filter.setClearButtonVisible(true);
        filter.setValueChangeMode(ValueChangeMode.LAZY);
        filter.addValueChangeListener(_ -> applyFilter());

        refreshButton.addClickListener(_ -> refresh());
        refreshButton.addThemeVariants(ButtonVariant.TERTIARY);

        // Quotas are created with users in the users view, so there is no button for a new one here
        var toolbar = new HorizontalLayout(filter, refreshButton);
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);

        // Headers are set in localeChange
        idColumn = grid.addColumn(QuotaRow::id).setSortable(true)
                .setComparator(Comparator.comparing(QuotaRow::id)).setFlexGrow(2).setFrozen(true);
        periodColumn = grid.addColumn(row -> label(row.definition().period())).setAutoWidth(true);
        usageColumn = grid.addColumn(new ComponentRenderer<>(row -> usageBadge(row.status())))
                .setTextAlign(ColumnTextAlign.END).setAutoWidth(true);
        resetsColumn = grid.addColumn(row -> dateTimeFormatter.format(row.status().resetAt())).setAutoWidth(true);
        lastUsedColumn = grid.addColumn(row -> row.lastUsed().map(dateTimeFormatter::format)
                        .orElse(getTranslation("quotas.never")))
                .setAutoWidth(true);
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
        var badge = new Badge(status.used() + " / " + status.limit());
        if (isUsedUp(status)) {
            badge.addThemeVariants(BadgeVariant.ERROR);
        } else if (isRunningLow(status)) {
            badge.addThemeVariants(BadgeVariant.WARNING);
        } else {
            badge.addThemeVariants(BadgeVariant.SUCCESS);
        }
        return badge;
    }

    private void showEditor(QuotaRow row) {
        masterDetail.setDetail(new QuotaEditor(row, userQuotaIds.contains(row.id()),
                quotaDefinitionRepository, quotaManager, dateTimeFormatter, this::onSaved, this::closeEditor));
    }

    private void onSaved(QuotaDefinition definition) {
        refresh();
        rows.stream().filter(row -> row.id().equals(definition.id())).findFirst().ifPresent(row -> {
            grid.select(row);
            showEditor(row);
        });
    }

    // Deselecting on the server does not close the editor by itself, as refreshes deselect too
    private void closeEditor() {
        grid.deselectAll();
        masterDetail.setDetail(null);
    }

    void refresh() {
        try {
            rows = StreamSupport.stream(quotaDefinitionRepository.findAll().spliterator(), false)
                    .filter(Objects::nonNull)
                    .map(definition -> QuotaRow.load(definition, quotaManager))
                    .sorted(Comparator.comparing(QuotaRow::id))
                    .toList();
            userQuotaIds = userAccountService.findQuotaUsernames();
        } catch (DataAccessException e) {
            rows = List.of();
            var notification = Notification.show(
                    getTranslation("quotas.loadFailed", e.getMostSpecificCause().getMessage()));
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

    static boolean isUsedUp(QuotaStatus status) {
        return status.used() >= status.limit();
    }

    // Less than 20% of the limit is left
    static boolean isRunningLow(QuotaStatus status) {
        return status.remaining() < status.limit() * 0.2;
    }

    // Translated name of a period, for the current UI locale
    static String label(QuotaPeriod period) {
        return I18NProvider.translate("period." + period.name().toLowerCase(Locale.ROOT));
    }
}
