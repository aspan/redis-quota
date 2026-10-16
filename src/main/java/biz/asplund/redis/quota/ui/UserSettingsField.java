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

import biz.asplund.redis.security.UserSettings;
import com.vaadin.flow.component.customfield.CustomField;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/// A user's language and appearance, each with Not set, which leaves it to the browser's cookie or language.
///
/// Used in the user editor and on the profile page.
class UserSettingsField extends CustomField<UserSettings> {

    private final List<Locale> locales;
    private final Select<Locale> localeField = new Select<>();
    private final Select<ColorSchemeChoice> colorSchemeField = new Select<>();

    /// Creates the field with `locales` as the languages to choose from.
    UserSettingsField(List<Locale> locales) {
        super(UserSettings.NONE);
        this.locales = locales;

        localeField.setLabel(getTranslation("userEditor.language"));
        localeField.setItems(locales);
        localeField.setItemLabelGenerator(locale -> locale == null ? getTranslation("userEditor.notSet")
                : LanguagePreference.displayName(locale));
        localeField.setEmptySelectionAllowed(true);
        localeField.setEmptySelectionCaption(getTranslation("userEditor.notSet"));
        localeField.setHelperText(getTranslation("userEditor.languageHint"));
        // Each language with its flag; the label generator still gives the text of the selected value
        localeField.setRenderer(new ComponentRenderer<>(locale -> locale == null
                ? new Span(getTranslation("userEditor.notSet"))
                : PreferenceMenuButton.withPrefix(new LanguageFlag(locale), LanguagePreference.displayName(locale))));

        colorSchemeField.setLabel(getTranslation("userEditor.colorScheme"));
        colorSchemeField.setItems(ColorSchemeChoice.values());
        colorSchemeField.setItemLabelGenerator(choice -> choice == null ? getTranslation("userEditor.notSet")
                : capitalize(getTranslation("colorScheme." + choice.key())));
        colorSchemeField.setEmptySelectionAllowed(true);
        colorSchemeField.setEmptySelectionCaption(getTranslation("userEditor.notSet"));
        colorSchemeField.setHelperText(getTranslation("userEditor.colorSchemeHint"));

        localeField.addValueChangeListener(_ -> updateValue());
        colorSchemeField.addValueChangeListener(_ -> updateValue());

        // One column across the field, with each select filling it
        var form = new FormLayout(localeField, colorSchemeField);
        form.setAutoResponsive(true);
        form.setMaxColumns(1);
        form.setExpandColumns(true);
        form.setExpandFields(true);
        form.setWidthFull();
        add(form);
        setWidthFull();
    }

    @Override
    protected UserSettings generateModelValue() {
        var locale = localeField.getValue();
        var choice = colorSchemeField.getValue();
        return new UserSettings(choice == null ? null : choice.key(), locale == null ? null : locale.toLanguageTag());
    }

    /// Shows `settings` in the selects; a stored language that is no longer configured shows as not set.
    @Override
    protected void setPresentationValue(UserSettings settings) {
        var value = settings == null ? UserSettings.NONE : settings;
        localeField.setValue(Optional.ofNullable(value.locale()).map(Locale::forLanguageTag)
                .filter(locales::contains).orElse(null));
        colorSchemeField.setValue(Optional.ofNullable(value.colorScheme()).map(ColorSchemeChoice::fromKey)
                .orElse(null));
    }

    /// Capitalizes the first letter of `text`, as the scheme names are written in lower case, to fit in the
    /// toolbar's "switch to" text.
    private String capitalize(String text) {
        return text.isEmpty() ? text : text.substring(0, 1).toUpperCase(getLocale()) + text.substring(1);
    }
}
