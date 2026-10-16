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

import biz.asplund.redis.security.AccountRuleException;
import biz.asplund.redis.security.Role;
import biz.asplund.redis.security.UserAccountService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.BeanValidationBinder;
import com.vaadin.flow.data.binder.ValidationException;
import com.vaadin.flow.data.renderer.ComponentRenderer;
import org.springframework.dao.DataAccessException;

import java.util.Locale;
import java.util.Map;

/// Adds and deletes the roles that can be given to users; the built-in `ADMIN` and `USER` roles stay.
class RolesDialog extends Dialog {

    private final UserAccountService userAccountService;
    private final Runnable onChanged;

    private final Grid<Role> grid = new Grid<>();
    private final TextField nameField = new TextField();
    /// Validates the name with the constraint on [Role], after storing it in upper case.
    private final BeanValidationBinder<Role> binder = new BeanValidationBinder<>(Role.class);
    private Map<Long, Long> userCounts = Map.of();

    /// Creates the dialog, which lists the roles at once.
    ///
    /// @param onChanged run after a role has been added or deleted
    RolesDialog(UserAccountService userAccountService, Runnable onChanged) {
        this.userAccountService = userAccountService;
        this.onChanged = onChanged;

        setHeaderTitle(getTranslation("roles.title"));
        var close = new Button(VaadinIcon.CLOSE.create(), _ -> close());
        close.setAriaLabel(getTranslation("editor.close"));
        close.addThemeVariants(ButtonVariant.TERTIARY);
        getHeader().add(close);
        setWidth("28rem");

        grid.addColumn(Role::getName).setHeader(getTranslation("roles.column.name")).setFlexGrow(1);
        grid.addColumn(role -> userCounts.getOrDefault(role.getId(), 0L))
                .setHeader(getTranslation("roles.column.users")).setTextAlign(ColumnTextAlign.END).setAutoWidth(true)
                .setFlexGrow(0);
        grid.addColumn(new ComponentRenderer<>(this::deleteButton)).setAutoWidth(true).setFlexGrow(0);
        grid.setAllRowsVisible(true);

        nameField.setPlaceholder(getTranslation("roles.namePlaceholder"));
        nameField.setAriaLabel(getTranslation("roles.name"));
        nameField.setHelperText(getTranslation("roles.nameHint"));
        binder.forField(nameField)
                .withConverter(name -> name.trim().toUpperCase(Locale.ROOT), name -> name == null ? "" : name)
                .bind("name");
        binder.readBean(new Role());
        var add = new Button(getTranslation("roles.add"), VaadinIcon.PLUS.create(), _ -> addRole());
        var addRow = new HorizontalLayout(nameField, add);
        addRow.setWidthFull();
        addRow.setAlignItems(FlexComponent.Alignment.BASELINE);
        addRow.expand(nameField);

        var content = new VerticalLayout(grid, addRow);
        content.setPadding(false);
        add(content);
        refresh();
    }

    private Button deleteButton(Role role) {
        var delete = new Button(VaadinIcon.TRASH.create(), _ -> confirmDelete(role));
        delete.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.TERTIARY);
        delete.setAriaLabel(getTranslation("roles.delete", role.getName()));
        delete.setTooltipText(getTranslation("roles.delete", role.getName()));
        // The built-in ADMIN and USER roles cannot be deleted
        delete.setEnabled(!UserAccountService.BUILT_IN_ROLES.contains(role.getName()));
        return delete;
    }

    private void refresh() {
        try {
            userCounts = userAccountService.countUsersByRole();
            grid.setItems(userAccountService.findAllRoles());
        } catch (DataAccessException e) {
            notify(getTranslation("roles.loadFailed", e.getMostSpecificCause().getMessage()), NotificationVariant.ERROR);
        }
    }

    private void addRole() {
        var newRole = new Role();
        try {
            binder.writeBean(newRole);
        } catch (ValidationException e) {
            // The binder shows the error on the field
            return;
        }
        try {
            var role = userAccountService.createRole(newRole.getName());
            binder.readBean(new Role());
            notify(getTranslation("roles.added", role.getName()), NotificationVariant.SUCCESS);
            changed();
        } catch (AccountRuleException e) {
            nameField.setInvalid(true);
            nameField.setErrorMessage(UserView.ruleMessage(this, e));
        } catch (DataAccessException e) {
            notify(getTranslation("roles.saveFailed", e.getMostSpecificCause().getMessage()), NotificationVariant.ERROR);
        }
    }

    private void confirmDelete(Role role) {
        var dialog = new ConfirmDialog(getTranslation("roles.deleteTitle", role.getName()),
                getTranslation("roles.deleteText", userCounts.getOrDefault(role.getId(), 0L)),
                getTranslation("editor.delete"), _ -> delete(role));
        dialog.setCancelText(getTranslation("editor.cancel"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.setCancelable(true);
        dialog.open();
    }

    private void delete(Role role) {
        try {
            userAccountService.deleteRole(role.getId());
            notify(getTranslation("roles.deleted", role.getName()), NotificationVariant.SUCCESS);
            changed();
        } catch (AccountRuleException e) {
            notify(UserView.ruleMessage(this, e), NotificationVariant.ERROR);
        } catch (DataAccessException e) {
            notify(getTranslation("roles.saveFailed", e.getMostSpecificCause().getMessage()), NotificationVariant.ERROR);
        }
    }

    private void changed() {
        refresh();
        onChanged.run();
    }

    private static void notify(String text, NotificationVariant variant) {
        Notification.show(text, 5000, Notification.Position.TOP_CENTER).addThemeVariants(variant);
    }
}
