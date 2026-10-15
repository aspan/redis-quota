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
import biz.asplund.redis.security.AccountRuleException;
import biz.asplund.redis.security.Role;
import biz.asplund.redis.security.UserAccount;
import biz.asplund.redis.security.UserAccountService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
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
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
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
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

// Lists the admin UI's users, and creates, edits and deletes them, their roles and quotas
@Route("users")
@PageTitle("users.title")
@Menu(title = "users.title", icon = "vaadin:users", order = 1)
@RolesAllowed("ADMIN")
public class UserView extends VerticalLayout implements BeforeEnterObserver, LocaleChangeObserver {

    private final UserAccountService userAccountService;
    private final QuotaManager quotaManager;
    private final ZoneId zone;
    private final String actingUsername;
    private DateTimeFormatter dateTimeFormatter;

    private final Grid<UserAccount> grid = new Grid<>();
    private final TextField filter = new TextField();
    private final Button refreshButton = new Button(VaadinIcon.REFRESH.create());
    private final Button rolesButton = new Button(VaadinIcon.KEY.create());
    private final Button addButton = new Button(VaadinIcon.PLUS.create());
    private final MasterDetailLayout masterDetail = new MasterDetailLayout();

    private Grid.Column<UserAccount> usernameColumn;
    private Grid.Column<UserAccount> rolesColumn;
    private Grid.Column<UserAccount> quotaColumn;
    private Grid.Column<UserAccount> statusColumn;

    private List<UserAccount> users = List.of();
    private List<Role> roles = List.of();

    public UserView(UserAccountService userAccountService, QuotaManager quotaManager, Clock clock,
            AuthenticationContext authenticationContext) {
        this.userAccountService = userAccountService;
        this.quotaManager = quotaManager;
        this.zone = clock.getZone();
        this.actingUsername = authenticationContext.getPrincipalName().orElse("");
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

        filter.setPlaceholder(getTranslation("users.filter"));
        refreshButton.setTooltipText(getTranslation("users.refresh"));
        refreshButton.setAriaLabel(getTranslation("users.refresh"));
        rolesButton.setText(getTranslation("users.roles"));
        addButton.setText(getTranslation("users.new"));

        usernameColumn.setHeader(getTranslation("users.column.username"));
        rolesColumn.setHeader(getTranslation("users.column.roles"));
        quotaColumn.setHeader(getTranslation("users.column.quota"));
        statusColumn.setHeader(getTranslation("users.column.status"));
        grid.setEmptyStateText(getTranslation("users.empty"));
        grid.getDataProvider().refreshAll();

        // Rebuild an open editor in the new language; unsaved input in it is lost
        if (masterDetail.getDetail() instanceof UserEditor editor) {
            showEditor(findUser(editor.userId()));
        }
    }

    // As in the quotas view, for the reset and last-used times of a user's quota
    private DateTimeFormatter createDateTimeFormatter(Locale locale) {
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).withZone(zone);
    }

    // Reload on every navigation, including to this view when it is already shown
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        refresh();
    }

    private Component createMaster() {
        filter.setWidth("16rem");
        filter.setPrefixComponent(VaadinIcon.SEARCH.create());
        filter.setClearButtonVisible(true);
        filter.setValueChangeMode(ValueChangeMode.LAZY);
        filter.addValueChangeListener(_ -> applyFilter());

        refreshButton.addClickListener(_ -> refresh());
        refreshButton.addThemeVariants(ButtonVariant.TERTIARY);

        rolesButton.addClickListener(_ -> new RolesDialog(userAccountService, this::refresh).open());

        addButton.addClickListener(_ -> {
            grid.deselectAll();
            showEditor(null);
        });
        addButton.addThemeVariants(ButtonVariant.PRIMARY);

        var toolbar = new HorizontalLayout(filter, refreshButton, rolesButton, addButton);
        toolbar.setWidthFull();
        toolbar.setAlignItems(FlexComponent.Alignment.CENTER);
        rolesButton.getStyle().set("margin-inline-start", "auto");

        // Headers are set in localeChange
        usernameColumn = grid.addColumn(UserAccount::getUsername).setSortable(true)
                .setComparator(Comparator.comparing(UserAccount::getUsername)).setFlexGrow(2).setFrozen(true);
        rolesColumn = grid.addColumn(UserView::roleNames).setFlexGrow(1);
        quotaColumn = grid.addColumn(account -> account.getQuota()
                        .map(quota -> getTranslation("users.quota", quota.limit(), QuotaView.label(quota.period())))
                        .orElse(getTranslation("userEditor.noQuota")))
                .setAutoWidth(true).setFlexGrow(0);
        statusColumn = grid.addColumn(new ComponentRenderer<>(this::statusBadge)).setAutoWidth(true).setFlexGrow(0);
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

    static String roleNames(UserAccount account) {
        return account.getRoles().stream().map(Role::getName).sorted().collect(Collectors.joining(", "));
    }

    private Component statusBadge(UserAccount account) {
        var badge = new Badge(getTranslation(account.isEnabled() ? "users.enabled" : "users.disabled"));
        badge.addThemeVariants(account.isEnabled() ? BadgeVariant.SUCCESS : BadgeVariant.CONTRAST);
        return badge;
    }

    private void showEditor(UserAccount account) {
        masterDetail.setDetail(new UserEditor(account, roles, userAccountService, quotaManager, dateTimeFormatter,
                actingUsername, this::onSaved, this::onDeleted, this::closeEditor));
    }

    private void onSaved(UserAccount account) {
        refresh();
        var saved = findUser(account.getId());
        if (saved != null) {
            grid.select(saved);
            showEditor(saved);
        }
    }

    private void onDeleted() {
        closeEditor();
        refresh();
    }

    // Deselecting on the server does not close the editor by itself, as refreshes deselect too
    private void closeEditor() {
        grid.deselectAll();
        masterDetail.setDetail(null);
    }

    private UserAccount findUser(Long id) {
        return id == null ? null : users.stream().filter(user -> user.getId().equals(id)).findFirst().orElse(null);
    }

    void refresh() {
        try {
            users = userAccountService.findAllUsers();
            roles = userAccountService.findAllRoles();
        } catch (DataAccessException e) {
            users = List.of();
            Notification.show(getTranslation("users.loadFailed", e.getMostSpecificCause().getMessage()))
                    .addThemeVariants(NotificationVariant.ERROR);
        }
        applyFilter();

        // Keep the open editor showing current roles, which the roles dialog may have changed
        if (masterDetail.getDetail() instanceof UserEditor editor) {
            var account = findUser(editor.userId());
            if (account != null) {
                grid.select(account);
            }
            if (account != null || editor.userId() == null) {
                showEditor(account);
            } else {
                closeEditor();
            }
        }
    }

    private void applyFilter() {
        var term = filter.getValue().trim().toLowerCase(Locale.ROOT);
        grid.setItems(term.isEmpty() ? users
                : users.stream().filter(user -> user.getUsername().toLowerCase(Locale.ROOT).contains(term)).toList())
                .setIdentifierProvider(UserAccount::getId);
    }

    // The translated message for a rule the service refused a change by
    static String ruleMessage(Component component, AccountRuleException e) {
        return component.getTranslation("users.rule." + e.rule().name());
    }
}
