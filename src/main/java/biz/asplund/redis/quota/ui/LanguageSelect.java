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

import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;
import java.util.Locale;

// Picks the UI language; the choice applies to every open tab of the session and is remembered in a cookie
class LanguageSelect extends Select<Locale> implements LocaleChangeObserver {

    LanguageSelect(LanguagePreference languagePreference) {
        setItems(languagePreference.locales());
        setItemLabelGenerator(LanguageSelect::displayName);
        setPrefixComponent(VaadinIcon.GLOBE.create());
        addValueChangeListener(event -> {
            if (event.isFromClient() && event.getValue() != null) {
                languagePreference.select(event.getValue());
            }
        });
    }

    @Override
    public void localeChange(LocaleChangeEvent event) {
        setAriaLabel(getTranslation("language.label"));
        setTooltipText(getTranslation("language.label"));
        setValue(I18nConfiguration.supported(event.getLocale()));
    }

    // Each language is named in itself, so it can be found whatever the current language is
    private static String displayName(Locale locale) {
        var name = locale.getDisplayLanguage(locale);
        return name.isEmpty() ? locale.toLanguageTag() : name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }
}
