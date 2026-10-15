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

import com.vaadin.flow.server.VaadinResponse;
import com.vaadin.flow.server.VaadinSession;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;

// Reads and stores the user's color scheme: the logged-in user's setting, or else a cookie, like the language in
// LanguagePreference
@Component
public class ColorSchemePreference {

    static final String COOKIE = "color-scheme";

    private final LoggedInUserSettings loggedInUserSettings;

    ColorSchemePreference(LoggedInUserSettings loggedInUserSettings) {
        this.loggedInUserSettings = loggedInUserSettings;
    }

    // The logged-in user's choice, or else the one stored in the cookie, or SYSTEM
    ColorSchemeChoice resolve(HttpServletRequest request) {
        var userChoice = loggedInUserSettings.get().colorScheme();
        if (userChoice != null) {
            return ColorSchemeChoice.fromKey(userChoice);
        }
        return Optional.ofNullable(request.getCookies()).stream().flatMap(Arrays::stream)
                .filter(cookie -> COOKIE.equals(cookie.getName())).findFirst()
                .map(cookie -> ColorSchemeChoice.fromKey(cookie.getValue()))
                .orElse(ColorSchemeChoice.SYSTEM);
    }

    // The choice of the current session, as resolved when its UIs started or picked since
    ColorSchemeChoice current() {
        var choice = VaadinSession.getCurrent().getAttribute(ColorSchemeChoice.class);
        return choice != null ? choice : ColorSchemeChoice.SYSTEM;
    }

    // Applies the choice to every UI of the session and remembers it for the logged-in user and in the cookie
    void select(ColorSchemeChoice choice) {
        loggedInUserSettings.change(settings -> settings.withColorScheme(choice.key()));
        var session = VaadinSession.getCurrent();
        session.setAttribute(ColorSchemeChoice.class, choice);
        session.getUIs().forEach(ui -> ui.getPage().setColorScheme(choice.value()));

        if (VaadinResponse.getCurrent() instanceof VaadinResponse response) {
            var cookie = new Cookie(COOKIE, choice.key());
            cookie.setPath("/");
            cookie.setMaxAge((int) Duration.ofDays(365).toSeconds());
            cookie.setHttpOnly(true);
            cookie.setAttribute("SameSite", "Lax");
            response.addCookie(cookie);
        }
    }

    // Sets the scheme in the page Vaadin sends first, the same way Page.setColorScheme does in the browser,
    // so a stored dark scheme does not flash light while the app loads
    static void apply(Document document, ColorSchemeChoice choice) {
        var html = document.selectFirst("html");
        if (html == null) {
            return;
        }
        var theme = choice.value().getThemeValue();
        if (theme == null || theme.isEmpty() || "normal".equals(theme)) {
            html.removeAttr("theme");
        } else {
            html.attr("theme", theme);
        }
        // Replaces the color-scheme Vaadin set from @ColorScheme, keeping any other styles
        var style = html.attr("style").replaceAll("color-scheme\\s*:[^;]*;?", "").trim();
        html.attr("style", (style.isEmpty() ? "" : style + " ") + "color-scheme: " + choice.value().getValue() + ";");
    }
}
