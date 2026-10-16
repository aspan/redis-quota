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

import com.vaadin.flow.component.Component;

import java.util.List;
import java.util.Locale;

/// Picks the UI language; the choice applies to every open tab of the session and is remembered in a cookie.
class LanguageMenuButton extends PreferenceMenuButton<Locale> {

    private final LanguagePreference languagePreference;

    /// Creates the button for the languages of the given preference.
    LanguageMenuButton(LanguagePreference languagePreference) {
        this.languagePreference = languagePreference;
    }

    @Override
    List<Locale> choices() {
        return languagePreference.locales();
    }

    @Override
    Locale current() {
        return languagePreference.supported(getLocale());
    }

    @Override
    String label(Locale locale) {
        return LanguagePreference.displayName(locale);
    }

    @Override
    Component prefix(Locale locale) {
        return new LanguageFlag(locale);
    }

    /// Switching the language calls `localeChange`, which updates this button.
    @Override
    void select(Locale locale) {
        languagePreference.select(locale);
    }

    /// The current language's flag; the button's accessible name and tooltip say it chooses the language.
    @Override
    void showOnButton(Locale current) {
        var flag = new LanguageFlag(current);
        // As high as the other icons at the top right, and 4:3 like the SVGs
        flag.setWidth("calc(" + MainLayout.TOOLBAR_ICON_SIZE + " * 4 / 3)");
        flag.setHeight(MainLayout.TOOLBAR_ICON_SIZE);
        setIcon(flag);
    }

    @Override
    String labelKey() {
        return "language.label";
    }
}
