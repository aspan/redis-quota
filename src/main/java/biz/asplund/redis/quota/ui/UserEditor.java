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
import biz.asplund.redis.security.AccountRuleException;
import biz.asplund.redis.security.QuotaHistoryService;
import biz.asplund.redis.security.Role;
import biz.asplund.redis.security.UserAccount;
import biz.asplund.redis.security.UserAccountService;
import biz.asplund.redis.sms.SmsService;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
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
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.BeanValidationBinder;
import com.vaadin.flow.data.binder.ValidationException;
import org.springframework.dao.DataAccessException;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/// Detail panel for creating or editing one user: password, whether it may log in, its roles, quota, the sender ids
/// it may send SMS from and settings, and for a user with a quota, its usage in the current period and in finished
/// periods.
class UserEditor extends VerticalLayout {

    private final UserAccount account;
    private final UserAccountService userAccountService;
    private final QuotaManager quotaManager;
    private final DateTimeFormatter dateTimeFormatter;
    private final QuotaHistoryService quotaHistoryService;
    private final String actingUsername;
    private final Consumer<UserAccount> onSaved;
    private final Runnable onDeleted;

    private final TextField usernameField = new TextField();
    private final TextField nameField = new TextField();
    private final EmailField emailField = new EmailField();
    private final PasswordField passwordField = new PasswordField();
    private final Checkbox enabledField = new Checkbox();
    private final MultiSelectComboBox<Role> rolesField = new MultiSelectComboBox<>();
    private final Select<QuotaPeriod> quotaPeriodField = new Select<>();
    private final IntegerField quotaLimitField = new IntegerField();
    private final MultiSelectComboBox<String> senderIdsField = new MultiSelectComboBox<>();
    /// The sender ids to choose from: the user's own and those typed in since the editor opened.
    private final List<String> senderIdItems = new ArrayList<>();
    /// Validates with the constraints on [UserAccount] and the rules below, and writes the fields into a new
    /// [UserAccount], so the user shown in the grid is not changed before the service has accepted the change.
    private final BeanValidationBinder<UserAccount> binder = new BeanValidationBinder<>(UserAccount.class);

    /// Creates the panel for `account`, or for a new user if it is `null`.
    ///
    /// @param roles          the roles the user can be given
    /// @param locales        the languages the user's settings can choose from
    /// @param actingUsername the logged-in user, who cannot disable, delete or take the `ADMIN` role from themselves
    /// @param onSaved        run with the saved user after a save, and with `account` after its usage has been changed
    /// @param onDeleted      run after the user has been deleted
    /// @param onClose        run when the panel's close button is clicked
    UserEditor(UserAccount account, List<Role> roles, List<Locale> locales, UserAccountService userAccountService,
               QuotaManager quotaManager, QuotaHistoryService quotaHistoryService, DateTimeFormatter dateTimeFormatter,
               String actingUsername, Consumer<UserAccount> onSaved, Runnable onDeleted, Runnable onClose) {
        this.account = account;
        this.userAccountService = userAccountService;
        this.quotaManager = quotaManager;
        this.dateTimeFormatter = dateTimeFormatter;
        this.quotaHistoryService = quotaHistoryService;
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
        add(header, createForm(roles), new H4(getTranslation("userEditor.quota")), createQuotaForm(),
                new H4(getTranslation("userEditor.sms")), createSenderIdsForm(),
                new H4(getTranslation("userEditor.settings")), createSettingsForm(locales), createButtons());
        addUsage();
        addHistory();

        // A new user starts enabled, without roles or a quota
        binder.readBean(account != null ? account : new UserAccount());
        quotaLimitField.setEnabled(quotaPeriodField.getValue() != null);
    }

    /// Returns the id of the edited user, or `null` for a new one.
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

        nameField.setLabel(getTranslation("userEditor.name"));
        nameField.setHelperText(getTranslation("userEditor.nameHint"));
        nameField.setClearButtonVisible(true);
        // Not set is shown as empty, and an empty field clears the name
        binder.forField(nameField).withNullRepresentation("").bind("name");

        configureEmailField(emailField);
        binder.forField(emailField).withNullRepresentation("").bind("email");
        form.add(nameField, emailField);

        passwordField.setLabel(getTranslation("userEditor.password"));
        passwordField.setRequired(account == null);
        passwordField.setHelperText(getTranslation(account == null ? "userEditor.passwordHint"
                : "userEditor.passwordKeepHint", UserAccountService.MIN_PASSWORD_LENGTH));

        enabledField.setLabel(getTranslation("userEditor.enabled"));

        rolesField.setLabel(getTranslation("userEditor.roles"));
        rolesField.setItems(roles);
        rolesField.setItemLabelGenerator(Role::getName);
        rolesField.setSelectedItemsOnTop(true);

        // The service refuses these too; here the editor shows why before saving
        if (isOwnAccount()) {
            enabledField.setEnabled(false);
            enabledField.setHelperText(getTranslation("userEditor.ownAccountEnabled"));
            rolesField.setHelperText(getTranslation("userEditor.ownAccountAdmin"));
        }

        // The username is bound for an existing user too, though not shown, so every user is written whole
        binder.forField(usernameField)
                .withConverter(String::trim, username -> username == null ? "" : username).bind("username");
        // The entity holds the encoded password, so the field is only validated here and passed to the service
        binder.forField(passwordField)
                .withValidator(password -> password.isEmpty() ? account != null
                                : password.length() >= UserAccountService.MIN_PASSWORD_LENGTH,
                        getTranslation("users.rule.PASSWORD_TOO_SHORT"))
                .bind(user -> "", (user, password) -> {
                });
        binder.forField(enabledField).bind("enabled");
        binder.forField(rolesField)
                .withValidator(selected -> !isOwnAccount() || selected.stream()
                                .anyMatch(role -> UserAccountService.ADMIN_ROLE.equals(role.getName())),
                        getTranslation("users.rule.OWN_ADMIN_ROLE"))
                .bind("roles");

        form.add(passwordField, rolesField, enabledField);
        return form;
    }

    /// Creates the form for the quota counted under the username, as the `QuotaDefinition` the quotas view lists; no
    /// period means none.
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
        // No min, as in the quota editor, so @PositiveOrZero on UserAccount gives the message
        quotaLimitField.setStepButtonsVisible(false);

        binder.forField(quotaPeriodField).bind("quotaPeriod");
        // A period needs a limit; without a period the limit is cleared, so the user has no quota
        binder.forField(quotaLimitField)
                .withValidator(limit -> quotaPeriodField.getValue() == null || limit != null,
                        getTranslation("users.rule.QUOTA_LIMIT_INVALID"))
                .bind("quotaLimit");
        quotaPeriodField.addValueChangeListener(event -> {
            quotaLimitField.setEnabled(event.getValue() != null);
            if (event.getValue() == null) {
                quotaLimitField.clear();
            }
        });

        form.add(quotaPeriodField, quotaLimitField);
        return form;
    }

    /// Creates the field for the sender ids the user may send SMS from, typed in and normalized as the service stores
    /// them.
    private Component createSenderIdsForm() {
        if (account != null) {
            senderIdItems.addAll(account.getSenderIds().stream().sorted().toList());
        }
        senderIdsField.setLabel(getTranslation("userEditor.senderIds"));
        senderIdsField.setHelperText(getTranslation("userEditor.senderIdsHint",
                SmsService.MAX_ALPHANUMERIC_SENDER_ID_LENGTH));
        senderIdsField.setItems(senderIdItems);
        senderIdsField.setSelectedItemsOnTop(true);
        senderIdsField.setAllowCustomValue(true);
        senderIdsField.setWidthFull();
        senderIdsField.addCustomValueSetListener(event -> SmsService.normalizeSenderId(event.getDetail())
                .ifPresentOrElse(senderId -> {
                    if (!senderIdItems.contains(senderId)) {
                        senderIdItems.add(senderId);
                        senderIdsField.getListDataView().refreshAll();
                    }
                    senderIdsField.select(senderId);
                    senderIdsField.setInvalid(false);
                }, () -> {
                    senderIdsField.setInvalid(true);
                    senderIdsField.setErrorMessage(getTranslation("users.rule.SENDER_ID_INVALID"));
                }));
        binder.forField(senderIdsField).bind("senderIds");
        return senderIdsField;
    }

    /// Adds the usage of the saved quota, which the quota form above may since have been changed from.
    ///
    /// The quota is read from Redis, where it is copied when the user is saved.
    private void addUsage() {
        if (account == null || account.getQuota().isEmpty()) {
            return;
        }
        var username = account.getUsername();
        try {
            var status = quotaManager.getStatus(username);
            add(new H4(getTranslation("editor.currentUsage")));
            add(new QuotaUsageCard(username, account.getQuota().get().period(), status,
                    quotaManager.getLastUsed(username), quotaManager, dateTimeFormatter, () -> onSaved.accept(account)));
        } catch (IllegalArgumentException e) {
            // The copy in Redis is missing; saving the user writes it again
            add(new Span(getTranslation("userEditor.usageMissing")));
        }
    }

    /// Adds the final usage of the user's finished periods, for a user who has or had a quota.
    private void addHistory() {
        if (account == null) {
            return;
        }
        var history = new UsageHistorySection(quotaHistoryService, account.getUsername(), dateTimeFormatter);
        if (account.getQuota().isPresent() || history.hasHistory()) {
            add(history);
        }
    }

    /// Sets the label and error message of an email field, here and on the profile page.
    ///
    /// The field checks the address itself before the binder does, with its own message, so it gets the same one as
    /// the `@Email` constraint on [UserAccount].
    static void configureEmailField(EmailField field) {
        field.setLabel(field.getTranslation("userEditor.email"));
        field.setClearButtonVisible(true);
        field.setI18n(new EmailField.EmailFieldI18n()
                .setPatternErrorMessage(field.getTranslation("userEditor.emailInvalid")));
    }

    /// Creates the field for the language and appearance the user's pages open in, also set from the toolbar while
    /// the user is logged in.
    private Component createSettingsForm(List<Locale> locales) {
        var settingsField = new UserSettingsField(locales);
        binder.forField(settingsField).bind(UserAccount::getSettings, UserAccount::setSettings);
        return settingsField;
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
        var user = new UserAccount();
        try {
            binder.writeBean(user);
        } catch (ValidationException e) {
            // The binder shows the errors on the fields
            return;
        }

        var roleNames = user.getRoles().stream().map(Role::getName).toList();
        var quota = user.getQuota().orElse(null);
        try {
            var saved = account == null
                    ? userAccountService.createUser(user.getUsername(), passwordField.getValue(), user.isEnabled(),
                    roleNames, quota, user.getProfile(), user.getSenderIds())
                    : userAccountService.updateUser(account.getId(), passwordField.getValue(), user.isEnabled(),
                    roleNames, quota, user.getProfile(), user.getSenderIds(), actingUsername);
            notify(getTranslation("userEditor.saved", saved.getUsername()), NotificationVariant.SUCCESS);
            onSaved.accept(saved);
        } catch (AccountRuleException e) {
            // The rules the binder cannot check, such as a username already taken
            var message = UserView.ruleMessage(this, e);
            if (e.rule() == AccountRuleException.Rule.USERNAME_TAKEN) {
                usernameField.setInvalid(true);
                usernameField.setErrorMessage(message);
            } else if (e.rule() == AccountRuleException.Rule.EMAIL_TAKEN) {
                emailField.setInvalid(true);
                emailField.setErrorMessage(message);
            } else {
                notify(message, NotificationVariant.ERROR);
            }
        } catch (DataAccessException e) {
            notify(getTranslation("userEditor.saveFailed", e.getMostSpecificCause().getMessage()),
                    NotificationVariant.ERROR);
        }
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
        Notification.show(text, 5000, Notification.Position.TOP_CENTER).addThemeVariants(variant);
    }
}
