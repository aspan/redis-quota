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
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.contextmenu.ContextMenuPosition;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;

import java.util.List;

/// A toolbar button that opens a menu of choices, with the current choice checked.
///
/// @param <T> the type of the choices
abstract class PreferenceMenuButton<T> extends Button implements LocaleChangeObserver {

    private final ContextMenu menu = new ContextMenu(this);

    /// Creates the button with a menu that opens on click, below its end.
    PreferenceMenuButton() {
        addThemeVariants(ButtonVariant.TERTIARY);
        menu.setOpenOnClick(true);
        menu.setPosition(ContextMenuPosition.BOTTOM_END);
    }

    /// Returns the choices, in the order they are listed in the menu.
    abstract List<T> choices();

    /// Returns the current choice, which is checked in the menu.
    abstract T current();

    /// Returns the label of a choice in the menu, in the current language.
    abstract String label(T choice);

    /// Applies a choice picked in the menu.
    abstract void select(T choice);

    /// An icon shown before a choice's label in the menu, or `null` for none.
    Component prefix(T choice) {
        return null;
    }

    /// Shows the current choice on the button itself.
    abstract void showOnButton(T current);

    /// Translation key of the button's accessible name and tooltip.
    abstract String labelKey();

    /// Returns the menu of choices.
    ContextMenu menu() {
        return menu;
    }

    /// Labels are translated, so the menu is rebuilt whenever the language changes.
    @Override
    public void localeChange(LocaleChangeEvent event) {
        update();
    }

    /// The icon and the label side by side.
    static Component withPrefix(Component prefix, String label) {
        var layout = new HorizontalLayout(prefix, new Span(label));
        layout.setSpacing("var(--vaadin-gap-s)");
        layout.setAlignItems(FlexComponent.Alignment.CENTER);
        return layout;
    }

    /// Rebuilds the menu with the current choice checked, and updates the button to show it.
    void update() {
        var current = current();
        menu.removeAll();
        for (var choice : choices()) {
            var item = menu.addItem(label(choice), _ -> {
                select(choice);
                update();
            });
            // Before the label, which stays the item's text
            var prefix = prefix(choice);
            if (prefix != null) {
                prefix.getElement().getStyle().set("margin-inline-end", "var(--vaadin-gap-s)");
                item.addComponentAsFirst(prefix);
            }
            item.setCheckable(true);
            item.setChecked(choice.equals(current));
        }
        showOnButton(current);
        setAriaLabel(getTranslation(labelKey()));
        setTooltipText(getTranslation(labelKey()));
    }
}
