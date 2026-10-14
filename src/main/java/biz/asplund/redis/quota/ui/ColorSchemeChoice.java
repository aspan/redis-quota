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
import com.vaadin.flow.component.page.ColorScheme;

import java.util.Arrays;
import java.util.Locale;

// The color schemes a user can pick; SYSTEM follows the operating system, like @ColorScheme on QuotaApplication
enum ColorSchemeChoice {
    SYSTEM(ColorScheme.Value.LIGHT_DARK, VaadinIcon.CONTRAST),
    LIGHT(ColorScheme.Value.LIGHT, VaadinIcon.SUN),
    DARK(ColorScheme.Value.DARK, VaadinIcon.MOON);

    private final ColorScheme.Value value;
    private final VaadinIcon icon;

    ColorSchemeChoice(ColorScheme.Value value, VaadinIcon icon) {
        this.value = value;
        this.icon = icon;
    }

    ColorScheme.Value value() {
        return value;
    }

    VaadinIcon icon() {
        return icon;
    }

    // Stored in the cookie and used in translation keys
    String key() {
        return name().toLowerCase(Locale.ROOT);
    }

    static ColorSchemeChoice fromKey(String key) {
        return Arrays.stream(values()).filter(choice -> choice.key().equals(key)).findFirst().orElse(SYSTEM);
    }
}
