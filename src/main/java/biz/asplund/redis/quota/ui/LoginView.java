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

import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.login.LoginI18n;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;

@Route(value = "login", autoLayout = false)
@PageTitle("login.title")
@AnonymousAllowed
public class LoginView extends VerticalLayout implements BeforeEnterObserver, LocaleChangeObserver {

    private final LoginForm login = new LoginForm();

    public LoginView(LanguagePreference languagePreference, ColorSchemePreference colorSchemePreference) {
        setSizeFull();
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);

        login.setAction("login");
        login.setForgotPasswordButtonVisible(false);
        add(login, new HorizontalLayout(new LanguageSelect(languagePreference),
                new ColorSchemeSelect(colorSchemePreference)));
    }

    @Override
    public void localeChange(LocaleChangeEvent event) {
        login.setI18n(createI18n());
        // The router sets the browser tab title only on navigation
        event.getUI().getPage().setTitle(getTranslation("login.title"));
    }

    private LoginI18n createI18n() {
        var form = new LoginI18n.Form();
        form.setTitle(getTranslation("login.form.title"));
        form.setUsername(getTranslation("login.form.username"));
        form.setPassword(getTranslation("login.form.password"));
        form.setSubmit(getTranslation("login.form.submit"));

        var errorMessage = new LoginI18n.ErrorMessage();
        errorMessage.setTitle(getTranslation("login.error.title"));
        errorMessage.setMessage(getTranslation("login.error.message"));

        var i18n = new LoginI18n();
        i18n.setForm(form);
        i18n.setErrorMessage(errorMessage);
        return i18n;
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        login.setError(event.getLocation().getQueryParameters().getParameters().containsKey("error"));
    }
}
