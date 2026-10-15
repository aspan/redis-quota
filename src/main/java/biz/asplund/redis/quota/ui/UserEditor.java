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

import biz.asplund.redis.quota.QuotaConfig;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.redis.security.AccountRuleException;
import biz.asplund.redis.security.Role;
import biz.asplund.redis.security.UserAccount;
import biz.asplund.redis.security.UserAccountService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import org.springframework.dao.DataAccessException;

import java.util.List;
import java.util.function.Consumer;

// Detail panel for creating or editing one user: password, whether it may log in, its roles and its quota
class UserEditor extends VerticalLayout {

    private final UserAccount account;
    private final UserAccountService userAccountService;
    private final String actingUsername;
    private final Consumer<UserAccount> onSaved;
    private final Runnable onDeleted;

    private final TextField usernameField = new TextField();
    private final PasswordField passwordField = new PasswordField();
    private final Checkbox enabledField = new Checkbox();
    private final MultiSelectComboBox<String> rolesField = new MultiSelectComboBox<>();
    private final Select<QuotaPeriod> quotaPeriodField = new Select<>();
    private final IntegerField quotaLimitField = new IntegerField();

    UserEditor(UserAccount account, List<Role> roles, UserAccountService userAccountService, String actingUsername,
            Consumer<UserAccount> onSaved, Runnable onDeleted, Runnable onClose) {
        this.account = account;
        this.userAccountService = userAccountService;
        this.actingUsername = actingUsername;
        this.onSaved = onSaved;
        this.onDeleted = onDeleted;

        setWidthFull();

        var title = new H3(account == null ? getTranslation("userEditor.newTitle") : account.getUsername());
        var close = new Button(VaadinIcon.CLOSE.create(), _ -> onClose.run());
        close.setAriaLabel(getTranslation("editor.close"));
        close.addThemeVariants(ButtonVariant.TERTIARY);
        var header = new HorizontalLayout(title, close);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.expand(title);
        add(header, createForm(roles), new H4(getTranslation("userEditor.quota")), createQuotaForm(), createButtons());
    }

    // Id of the edited user, or null for a new one
    Long userId() {
        return account == null ? null : account.getId();
    }

    private boolean isOwnAccount() {
        return account != null && account.getUsername().equals(actingUsername);
    }

    private Component createForm(List<Role> roles) {
        // One column across the editor, with each field filling it, as in the quota editor
        var form = new FormLayout();
        form.setAutoResponsive(true);
        form.setMaxColumns(1);
        form.setExpandColumns(true);
        form.setExpandFields(true);
        form.setWidthFull();

        if (account == null) {
            usernameField.setLabel(getTranslation("userEditor.username"));
            usernameField.setRequired(true);
            form.add(usernameField);
        }

        passwordField.setLabel(getTranslation("userEditor.password"));
        passwordField.setRequired(account == null);
        passwordField.setHelperText(getTranslation(account == null ? "userEditor.passwordHint"
                : "userEditor.passwordKeepHint", UserAccountService.MIN_PASSWORD_LENGTH));

        enabledField.setLabel(getTranslation("userEditor.enabled"));
        enabledField.setValue(account == null || account.isEnabled());

        rolesField.setLabel(getTranslation("userEditor.roles"));
        rolesField.setItems(roles.stream().map(Role::getName).toList());
        rolesField.setSelectedItemsOnTop(true);
        if (account != null) {
            rolesField.setValue(account.getRoles().stream().map(Role::getName).toList());
        }

        // The service refuses these too; here the editor shows why
        if (isOwnAccount()) {
            enabledField.setEnabled(false);
            enabledField.setHelperText(getTranslation("userEditor.ownAccountEnabled"));
            rolesField.setHelperText(getTranslation("userEditor.ownAccountAdmin"));
        }

        form.add(passwordField, rolesField, enabledField);
        return form;
    }

    // The quota counted under the username, as the QuotaDefinition the quotas view lists; no period means none
    private Component createQuotaForm() {
        var form = new FormLayout();
        form.setAutoResponsive(true);
        form.setMaxColumns(1);
        form.setExpandColumns(true);
        form.setExpandFields(true);
        form.setWidthFull();

        quotaPeriodField.setLabel(getTranslation("editor.period"));
        quotaPeriodField.setItems(QuotaPeriod.values());
        quotaPeriodField.setItemLabelGenerator(period -> period == null ? getTranslation("userEditor.noQuota")
                : QuotaView.label(period));
        quotaPeriodField.setEmptySelectionAllowed(true);
        quotaPeriodField.setEmptySelectionCaption(getTranslation("userEditor.noQuota"));
        quotaPeriodField.setHelperText(getTranslation("userEditor.quotaHint"));

        quotaLimitField.setLabel(getTranslation("editor.limit"));
        quotaLimitField.setMin(0);
        quotaLimitField.setStepButtonsVisible(false);

        if (account != null) {
            account.getQuota().ifPresent(quota -> {
                quotaPeriodField.setValue(quota.period());
                quotaLimitField.setValue(quota.limit());
            });
        }
        quotaLimitField.setEnabled(quotaPeriodField.getValue() != null);
        quotaPeriodField.addValueChangeListener(event -> {
            quotaLimitField.setEnabled(event.getValue() != null);
            quotaLimitField.setInvalid(false);
        });

        form.add(quotaPeriodField, quotaLimitField);
        return form;
    }

    // Null when no period is chosen; a missing limit is passed on as -1 for the service to reject
    private QuotaConfig quota() {
        if (quotaPeriodField.getValue() == null) {
            return null;
        }
        var limit = quotaLimitField.getValue();
        return new QuotaConfig(limit == null ? -1 : limit, quotaPeriodField.getValue());
    }

    private Component createButtons() {
        var save = new Button(getTranslation("editor.save"), _ -> save());
        save.addThemeVariants(ButtonVariant.PRIMARY);
        var buttons = new HorizontalLayout(save);
        if (account != null && !isOwnAccount()) {
            var delete = new Button(getTranslation("editor.delete"), VaadinIcon.TRASH.create(), _ -> confirmDelete());
            delete.addThemeVariants(ButtonVariant.ERROR, ButtonVariant.TERTIARY);
            buttons.add(delete);
        }
        return buttons;
    }

    private void save() {
        usernameField.setInvalid(false);
        passwordField.setInvalid(false);
        rolesField.setInvalid(false);
        quotaLimitField.setInvalid(false);
        try {
            var saved = account == null
                    ? userAccountService.createUser(usernameField.getValue(), passwordField.getValue(),
                            enabledField.getValue(), rolesField.getValue(), quota())
                    : userAccountService.updateUser(account.getId(), passwordField.getValue(),
                            enabledField.getValue(), rolesField.getValue(), quota(), actingUsername);
            notify(getTranslation("userEditor.saved", saved.getUsername()), NotificationVariant.SUCCESS);
            onSaved.accept(saved);
        } catch (AccountRuleException e) {
            var message = UserView.ruleMessage(this, e);
            switch (e.rule()) {
                case USERNAME_REQUIRED, USERNAME_TAKEN -> invalid(usernameField, message);
                case PASSWORD_TOO_SHORT -> invalid(passwordField, message);
                case QUOTA_LIMIT_INVALID -> {
                    quotaLimitField.setInvalid(true);
                    quotaLimitField.setErrorMessage(message);
                }
                case OWN_ADMIN_ROLE -> {
                    rolesField.setInvalid(true);
                    rolesField.setErrorMessage(message);
                }
                default -> notify(message, NotificationVariant.ERROR);
            }
        } catch (DataAccessException e) {
            notify(getTranslation("userEditor.saveFailed", e.getMostSpecificCause().getMessage()),
                    NotificationVariant.ERROR);
        }
    }

    private static void invalid(TextField field, String message) {
        field.setInvalid(true);
        field.setErrorMessage(message);
    }

    private static void invalid(PasswordField field, String message) {
        field.setInvalid(true);
        field.setErrorMessage(message);
    }

    private void confirmDelete() {
        var dialog = new ConfirmDialog(getTranslation("userEditor.deleteTitle", account.getUsername()),
                getTranslation("userEditor.deleteText"), getTranslation("editor.delete"), _ -> delete());
        dialog.setCancelText(getTranslation("editor.cancel"));
        dialog.setConfirmButtonTheme("error primary");
        dialog.setCancelable(true);
        dialog.open();
    }

    private void delete() {
        try {
            userAccountService.deleteUser(account.getId(), actingUsername);
            notify(getTranslation("userEditor.deleted", account.getUsername()), NotificationVariant.SUCCESS);
            onDeleted.run();
        } catch (AccountRuleException e) {
            notify(UserView.ruleMessage(this, e), NotificationVariant.ERROR);
        } catch (DataAccessException e) {
            notify(getTranslation("userEditor.deleteFailed", e.getMostSpecificCause().getMessage()),
                    NotificationVariant.ERROR);
        }
    }

    private static void notify(String text, NotificationVariant variant) {
        Notification.show(text).addThemeVariants(variant);
    }
}
