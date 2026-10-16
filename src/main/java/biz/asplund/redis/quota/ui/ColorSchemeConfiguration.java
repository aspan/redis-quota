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
import com.vaadin.flow.server.VaadinServiceInitListener;
import com.vaadin.flow.server.VaadinServletRequest;
import com.vaadin.flow.server.VaadinSession;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/// Applies the color scheme stored by [ColorSchemePreference] to each page load and new UI.
@Configuration
public class ColorSchemeConfiguration {

    /// Sets the resolved color scheme in the page Vaadin sends first, so it does not flash in the wrong scheme, and
    /// stores it in the session for each new UI.
    @Bean
    VaadinServiceInitListener colorSchemeFromCookie(ColorSchemePreference colorSchemePreference) {
        return event -> {
            event.addIndexHtmlRequestListener(response -> {
                if (response.getVaadinRequest() instanceof VaadinServletRequest request) {
                    ColorSchemePreference.apply(response.getDocument(),
                            colorSchemePreference.resolve(request.getHttpServletRequest()));
                }
            });
            event.getSource().addUIInitListener(uiEvent -> {
                if (VaadinRequest.getCurrent() instanceof VaadinServletRequest request) {
                    var choice = colorSchemePreference.resolve(request.getHttpServletRequest());
                    VaadinSession.getCurrent().setAttribute(ColorSchemeChoice.class, choice);
                    // Keeps the server's view of the page in line with the page sent above
                    uiEvent.getUI().getPage().setColorScheme(choice.value());
                }
            });
        };
    }
}
