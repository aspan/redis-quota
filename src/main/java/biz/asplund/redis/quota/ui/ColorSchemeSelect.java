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

import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;

// Picks light, dark or the system color scheme; the choice applies to the session and is remembered in a cookie
class ColorSchemeSelect extends Select<ColorSchemeChoice> implements LocaleChangeObserver {

    ColorSchemeSelect(ColorSchemePreference colorSchemePreference) {
        setItems(ColorSchemeChoice.values());
        setValue(colorSchemePreference.current());
        updateIcon();
        addValueChangeListener(event -> {
            updateIcon();
            if (event.isFromClient() && event.getValue() != null) {
                colorSchemePreference.select(event.getValue());
            }
        });
    }

    @Override
    public void localeChange(LocaleChangeEvent event) {
        // Setting the generator again renders the item labels in the new language
        setItemLabelGenerator(choice -> getTranslation("colorScheme." + choice.key()));
        setAriaLabel(getTranslation("colorScheme.label"));
        setTooltipText(getTranslation("colorScheme.label"));
    }

    private void updateIcon() {
        setPrefixComponent((getValue() != null ? getValue() : ColorSchemeChoice.SYSTEM).icon().create());
    }
}
