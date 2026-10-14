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

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;
import java.util.Locale;

/**
 * Languages of the admin UI.
 *
 * @param locales the locales the UI is translated to, each with a translations file in vaadin-i18n, such as
 *                translations_sv.properties for sv; the first is used for browsers that accept none of them
 */
@ConfigurationProperties("quota.ui.i18n")
public record I18nProperties(@DefaultValue({"en", "sv"}) List<Locale> locales) {

    public I18nProperties {
        if (locales == null || locales.isEmpty()) {
            throw new IllegalArgumentException("quota.ui.i18n.locales must name at least one locale");
        }
        locales = List.copyOf(locales);
    }

    Locale fallback() {
        return locales.getFirst();
    }

    // A configured locale for the given one, such as sv for sv_SE, or the fallback
    Locale supported(Locale locale) {
        if (locale == null) {
            return fallback();
        }
        return locales.stream().filter(locale::equals).findFirst()
                .or(() -> locales.stream().filter(l -> l.getLanguage().equals(locale.getLanguage())).findFirst())
                .orElse(fallback());
    }

    // The best configured match for the Accept-Language header, honouring its quality values
    Locale fromAcceptLanguage(HttpServletRequest request) {
        var header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return fallback();
        }
        try {
            var match = Locale.lookup(Locale.LanguageRange.parse(header), locales);
            return match != null ? match : fallback();
        } catch (IllegalArgumentException e) {
            return fallback();
        }
    }
}
