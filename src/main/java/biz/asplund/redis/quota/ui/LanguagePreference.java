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

// Reads and stores the user's language through Spring's LocaleResolver
@Component
public class LanguagePreference {

    private final LocaleResolver localeResolver;
    private final I18nProperties properties;

    LanguagePreference(LocaleResolver localeResolver, I18nProperties properties) {
        this.localeResolver = localeResolver;
        this.properties = properties;
    }

    List<Locale> locales() {
        return properties.locales();
    }

    // A configured locale for the given one, such as sv for sv_SE, or the fallback
    Locale supported(Locale locale) {
        return properties.supported(locale);
    }

    // The stored choice, or else the best match for the browser's languages
    Locale resolve(HttpServletRequest request) {
        return properties.supported(localeResolver.resolveLocale(request));
    }

    // Switches every UI of the session, which notifies each LocaleChangeObserver, and remembers the choice
    void select(Locale locale) {
        VaadinSession.getCurrent().setLocale(locale);
        if (VaadinRequest.getCurrent() instanceof VaadinServletRequest request
                && VaadinResponse.getCurrent() instanceof VaadinServletResponse response) {
            localeResolver.setLocale(request.getHttpServletRequest(), response.getHttpServletResponse(), locale);
        }
    }
}
