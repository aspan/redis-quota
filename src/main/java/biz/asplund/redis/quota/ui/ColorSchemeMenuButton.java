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

import java.util.List;

// Picks light, dark or the system color scheme; the choice applies to the session and is remembered in a cookie
class ColorSchemeMenuButton extends PreferenceMenuButton<ColorSchemeChoice> {

    private final ColorSchemePreference colorSchemePreference;

    ColorSchemeMenuButton(ColorSchemePreference colorSchemePreference) {
        this.colorSchemePreference = colorSchemePreference;
    }

    @Override
    List<ColorSchemeChoice> choices() {
        return List.of(ColorSchemeChoice.values());
    }

    @Override
    ColorSchemeChoice current() {
        return colorSchemePreference.current();
    }

    @Override
    String label(ColorSchemeChoice choice) {
        return getTranslation("colorScheme." + choice.key());
    }

    @Override
    void select(ColorSchemeChoice choice) {
        colorSchemePreference.select(choice);
    }

    @Override
    void showOnButton(ColorSchemeChoice current) {
        setIcon(current.icon().create());
    }

    @Override
    String labelKey() {
        return "colorScheme.label";
    }
}
