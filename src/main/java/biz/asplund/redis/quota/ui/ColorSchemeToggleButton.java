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

import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.ComponentUtil;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;
import com.vaadin.flow.shared.Registration;

/// Cycles through the system, light and dark color schemes; the choice applies to the session and is remembered
/// in a cookie.
class ColorSchemeToggleButton extends Button implements LocaleChangeObserver {

    private final ColorSchemePreference colorSchemePreference;
    private Registration changeRegistration;

    /// Creates the button, which switches to the next color scheme on click.
    ColorSchemeToggleButton(ColorSchemePreference colorSchemePreference) {
        this.colorSchemePreference = colorSchemePreference;
        addThemeVariants(ButtonVariant.TERTIARY);
        addClickListener(_ -> {
            colorSchemePreference.select(next(colorSchemePreference.current()));
            update();
        });
    }

    /// System, light, dark and back to system, in the order of the enum.
    private static ColorSchemeChoice next(ColorSchemeChoice current) {
        var choices = ColorSchemeChoice.values();
        return choices[(current.ordinal() + 1) % choices.length];
    }

    @Override
    public void localeChange(LocaleChangeEvent event) {
        update();
    }

    /// Follows changes made elsewhere, such as saving your own settings in the user editor or toggling in another
    /// tab.
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        changeRegistration = ComponentUtil.addListener(attachEvent.getUI(),
                ColorSchemePreference.ColorSchemeChangeEvent.class, _ -> update());
    }

    @Override
    protected void onDetach(DetachEvent detachEvent) {
        changeRegistration.remove();
        super.onDetach(detachEvent);
    }

    /// Shows the current scheme, and names the one a click switches to.
    private void update() {
        var current = colorSchemePreference.current();
        setIcon(MainLayout.toolbarIcon(current.icon()));
        var label = getTranslation("colorScheme.switchTo", getTranslation("colorScheme." + next(current).key()));
        setAriaLabel(label);
        setTooltipText(label);
    }
}
