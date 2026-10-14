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

import com.vaadin.flow.i18n.DefaultI18NProvider;
import com.vaadin.flow.i18n.I18NProvider;
import com.vaadin.flow.router.PageTitleGenerator;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinServletRequest;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.LocaleResolver;
import org.springframework.web.servlet.i18n.CookieLocaleResolver;

import java.time.Duration;
import java.util.List;
import java.util.Locale;

// Translations live in src/main/resources/vaadin-i18n. The language is the one picked in the UI,
// remembered in a cookie by Spring's LocaleResolver, or else the best match for the browser's languages
@Configuration
public class I18nConfiguration {

    // English comes first: it is the fallback for any language without translations
    static final List<Locale> LOCALES = List.of(Locale.ENGLISH, Locale.of("sv"));

    static final String LOCALE_COOKIE = "locale";

    @Bean
    I18NProvider i18nProvider() {
        return new DefaultI18NProvider(LOCALES);
    }

    // Views declare their title as a translation key in @PageTitle
    @Bean
    PageTitleGenerator pageTitleGenerator() {
        return context -> context.value() == null ? "" : I18NProvider.translate(context.value());
    }

    // Named localeResolver so that Spring MVC uses it too
    @Bean
    LocaleResolver localeResolver() {
        var localeResolver = new CookieLocaleResolver(LOCALE_COOKIE);
        localeResolver.setCookieMaxAge(Duration.ofDays(365));
        localeResolver.setCookieSameSite("Lax");
        localeResolver.setCookieHttpOnly(true);
        localeResolver.setDefaultLocaleFunction(I18nConfiguration::fromAcceptLanguage);
        return localeResolver;
    }

    // Each new UI, such as a page load or a new tab, starts in the resolved language
    @Bean
    VaadinServiceInitListener localeFromResolver(LanguagePreference languagePreference) {
        return event -> event.getSource().addUIInitListener(uiEvent -> {
            if (VaadinRequest.getCurrent() instanceof VaadinServletRequest request) {
                uiEvent.getUI().setLocale(languagePreference.resolve(request.getHttpServletRequest()));
            }
        });
    }

    // The best translated match for the Accept-Language header, honouring its quality values
    static Locale fromAcceptLanguage(HttpServletRequest request) {
        var header = request.getHeader("Accept-Language");
        if (header == null || header.isBlank()) {
            return LOCALES.getFirst();
        }
        try {
            var match = Locale.lookup(Locale.LanguageRange.parse(header), LOCALES);
            return match != null ? match : LOCALES.getFirst();
        } catch (IllegalArgumentException e) {
            return LOCALES.getFirst();
        }
    }

    // A translated locale for the given one, such as sv for sv_SE, or the fallback
    static Locale supported(Locale locale) {
        if (locale == null) {
            return LOCALES.getFirst();
        }
        return LOCALES.stream().filter(locale::equals).findFirst()
                .or(() -> LOCALES.stream().filter(l -> l.getLanguage().equals(locale.getLanguage())).findFirst())
                .orElse(LOCALES.getFirst());
    }
}
