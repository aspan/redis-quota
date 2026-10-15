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

import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinServletResponse;
import com.vaadin.flow.server.VaadinSession;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.LocaleResolver;

import java.util.List;
import java.util.Locale;

// Reads and stores the user's language: the logged-in user's setting, or else through Spring's LocaleResolver
@Component
public class LanguagePreference {

    private final LocaleResolver localeResolver;
    private final I18nProperties properties;
    private final LoggedInUserSettings loggedInUserSettings;

    LanguagePreference(LocaleResolver localeResolver, I18nProperties properties,
            LoggedInUserSettings loggedInUserSettings) {
        this.localeResolver = localeResolver;
        this.properties = properties;
        this.loggedInUserSettings = loggedInUserSettings;
    }

    List<Locale> locales() {
        return properties.locales();
    }

    // The language named in itself, such as Svenska, so it can be found whatever the current language is
    static String displayName(Locale locale) {
        var name = locale.getDisplayLanguage(locale);
        return name.isEmpty() ? locale.toLanguageTag() : name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    // A configured locale for the given one, such as sv for sv_SE, or the fallback
    Locale supported(Locale locale) {
        return properties.supported(locale);
    }

    // The logged-in user's language, or else the choice stored in the cookie, or else the best match for the
    // browser's languages
    Locale resolve(HttpServletRequest request) {
        var userLocale = loggedInUserSettings.get().locale();
        return properties.supported(userLocale != null ? Locale.forLanguageTag(userLocale)
                : localeResolver.resolveLocale(request));
    }

    // Switches every UI of the session, which notifies each LocaleChangeObserver, and remembers the choice for the
    // logged-in user and in the cookie
    void select(Locale locale) {
        VaadinSession.getCurrent().setLocale(locale);
        loggedInUserSettings.change(settings -> settings.withLocale(locale.toLanguageTag()));
        if (VaadinRequest.getCurrent() instanceof VaadinServletRequest request
                && VaadinResponse.getCurrent() instanceof VaadinServletResponse response) {
            localeResolver.setLocale(request.getHttpServletRequest(), response.getHttpServletResponse(), locale);
        }
    }

    // Applies the language the logged-in user's settings were just saved with: a set language as if picked in the
    // toolbar, or, when not set, the one the cookie or the browser gives
    void applySetting(String localeTag) {
        if (localeTag != null) {
            select(supported(Locale.forLanguageTag(localeTag)));
        } else if (VaadinRequest.getCurrent() instanceof VaadinServletRequest request) {
            VaadinSession.getCurrent().setLocale(resolve(request.getHttpServletRequest()));
        }
    }
}
