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
import biz.asplund.redis.security.UserAccountService;
import biz.asplund.redis.security.UserAccount;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.EmailField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.BeanValidationBinder;
import com.vaadin.flow.data.binder.ValidationException;
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import org.springframework.dao.DataAccessException;

/// The logged-in user's own page, open to every user: their language and appearance, which apply at once when
/// saved, and a new password, for which they confirm their current one.
@Route("profile")
@PageTitle("profile.title")
@Menu(title = "profile.title", icon = "vaadin:user", order = 3)
@PermitAll
public class ProfileView extends VerticalLayout implements LocaleChangeObserver {

    private final UserAccountService userAccountService;
    private final LanguagePreference languagePreference;
    private final ColorSchemePreference colorSchemePreference;
    private final String username;

    // Created in localeChange, so the page is built in the current language
    private TextField nameField;
    private EmailField emailField;
    private BeanValidationBinder<UserAccount> binder;
    private UserSettingsField settingsField;
    private PasswordField currentPasswordField;
    private PasswordField newPasswordField;
    private PasswordField repeatPasswordField;

    /// Creates the view for the logged-in user; the page itself is built in [#localeChange(LocaleChangeEvent)].
    public ProfileView(UserAccountService userAccountService, LanguagePreference languagePreference,
                       ColorSchemePreference colorSchemePreference, AuthenticationContext authenticationContext) {
        this.userAccountService = userAccountService;
        this.languagePreference = languagePreference;
        this.colorSchemePreference = colorSchemePreference;
        this.username = authenticationContext.getPrincipalName().orElse("");
        setMaxWidth("36rem");
    }

    /// Called when attached and whenever the language changes, as when the settings saved here change it; input
    /// not yet saved is lost.
    @Override
    public void localeChange(LocaleChangeEvent event) {
        removeAll();
        add(new Span(getTranslation("profile.username", username)), createSettings(), createPassword());
    }

    private Component createSettings() {
        nameField = new TextField(getTranslation("userEditor.name"));
        nameField.setHelperText(getTranslation("userEditor.nameHint"));
        nameField.setClearButtonVisible(true);
        nameField.setWidthFull();
        emailField = new EmailField();
        UserEditor.configureEmailField(emailField);
        emailField.setWidthFull();
        settingsField = new UserSettingsField(languagePreference.locales());

        // Validates with the constraints on UserAccount, as the user editor does
        binder = new BeanValidationBinder<>(UserAccount.class);
        binder.forField(nameField).withNullRepresentation("").bind("name");
        binder.forField(emailField).withNullRepresentation("").bind("email");
        binder.forField(settingsField).bind(UserAccount::getSettings, UserAccount::setSettings);
        var current = new UserAccount();
        current.setProfile(userAccountService.findProfile(username));
        binder.readBean(current);

        var save = new Button(getTranslation("profile.saveSettings"), _ -> saveSettings());
        save.addThemeVariants(ButtonVariant.PRIMARY);
        var layout = new VerticalLayout(new H4(getTranslation("profile.settings")), nameField, emailField,
                settingsField, save);
        layout.setPadding(false);
        return layout;
    }

    private Component createPassword() {
        currentPasswordField = new PasswordField();
        newPasswordField = new PasswordField();
        repeatPasswordField = new PasswordField();
        currentPasswordField.setLabel(getTranslation("profile.currentPassword"));
        newPasswordField.setLabel(getTranslation("profile.newPassword"));
        newPasswordField.setHelperText(getTranslation("userEditor.passwordHint",
                UserAccountService.MIN_PASSWORD_LENGTH));
        repeatPasswordField.setLabel(getTranslation("profile.repeatPassword"));

        var form = new FormLayout(currentPasswordField, newPasswordField, repeatPasswordField);
        form.setAutoResponsive(true);
        form.setMaxColumns(1);
        form.setExpandColumns(true);
        form.setExpandFields(true);
        form.setWidthFull();

        var change = new Button(getTranslation("profile.changePassword"), _ -> changePassword());
        var layout = new VerticalLayout(new H4(getTranslation("profile.password")), form, change);
        layout.setPadding(false);
        return layout;
    }

    /// Saves the settings for the user and applies them to the session at once, as when saving your own account in
    /// the user editor.
    private void saveSettings() {
        var changed = new UserAccount();
        try {
            binder.writeBean(changed);
        } catch (ValidationException e) {
            // The binder shows the errors on the fields
            return;
        }
        var profile = changed.getProfile();
        try {
            userAccountService.changeOwnProfile(username, profile);
        } catch (AccountRuleException e) {
            var message = UserView.ruleMessage(this, e);
            if (e.rule() == AccountRuleException.Rule.EMAIL_TAKEN) {
                emailField.setInvalid(true);
                emailField.setErrorMessage(message);
            } else {
                notify(message, NotificationVariant.ERROR);
            }
            return;
        } catch (DataAccessException e) {
            notify(getTranslation("profile.saveFailed", e.getMostSpecificCause().getMessage()),
                    NotificationVariant.ERROR);
            return;
        }
        notify(getTranslation("profile.settingsSaved"), NotificationVariant.SUCCESS);
        DisplayNameChangeEvent.fire();
        languagePreference.applySetting(profile.settings().locale());
        colorSchemePreference.applySetting(profile.settings().colorScheme());
    }

    private void changePassword() {
        currentPasswordField.setInvalid(false);
        newPasswordField.setInvalid(false);
        repeatPasswordField.setInvalid(false);
        if (!newPasswordField.getValue().equals(repeatPasswordField.getValue())) {
            invalid(repeatPasswordField, getTranslation("profile.passwordsDiffer"));
            return;
        }
        try {
            userAccountService.changeOwnPassword(username, currentPasswordField.getValue(),
                    newPasswordField.getValue());
        } catch (AccountRuleException e) {
            var message = UserView.ruleMessage(this, e);
            switch (e.rule()) {
                case WRONG_PASSWORD -> invalid(currentPasswordField, message);
                case PASSWORD_TOO_SHORT -> invalid(newPasswordField, message);
                default -> notify(message, NotificationVariant.ERROR);
            }
            return;
        } catch (DataAccessException e) {
            notify(getTranslation("profile.saveFailed", e.getMostSpecificCause().getMessage()),
                    NotificationVariant.ERROR);
            return;
        }
        currentPasswordField.clear();
        newPasswordField.clear();
        repeatPasswordField.clear();
        notify(getTranslation("profile.passwordChanged"), NotificationVariant.SUCCESS);
    }

    private static void invalid(PasswordField field, String message) {
        field.setInvalid(true);
        field.setErrorMessage(message);
    }

    private static void notify(String text, NotificationVariant variant) {
        Notification.show(text, 5000, Notification.Position.TOP_CENTER).addThemeVariants(variant);
    }
}
