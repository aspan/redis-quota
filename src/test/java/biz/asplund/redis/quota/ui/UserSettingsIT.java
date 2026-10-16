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

import biz.asplund.redis.security.UserAccount;
import biz.asplund.redis.security.UserAccountRepository;
import biz.asplund.redis.security.UserAccountService;
import biz.asplund.redis.security.UserSettings;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.server.VaadinRequest;
import com.vaadin.flow.server.VaadinServletRequest;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The language and appearance stored with a user: saved from the toolbar, preferred over the browser's cookies while
 * the user is logged in, and edited in the user editor.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@WithMockUser(username = UserSettingsIT.USERNAME, roles = "ADMIN")
class UserSettingsIT extends SpringBrowserlessTest {

    static final String USERNAME = "settings-user";

    @Autowired
    private UserAccountService userAccountService;

    @Autowired
    private UserAccountRepository userAccountRepository;

    @Autowired
    private LanguagePreference languagePreference;

    @Autowired
    private ColorSchemePreference colorSchemePreference;

    @BeforeEach
    void storeUserWithoutSettings() {
        if (!userAccountRepository.existsByUsername(USERNAME)) {
            userAccountService.createUser(USERNAME, "password1", true, List.of("ADMIN"), null);
        }
        userAccountService.changeSettings(USERNAME, _ -> UserSettings.NONE);
    }

    // Also afterwards, as the browserless setup starts the next test's UI before storeUserWithoutSettings runs, and
    // the UI reads the settings when it starts
    @AfterEach
    void clearSettings() {
        userAccountService.changeSettings(USERNAME, _ -> UserSettings.NONE);
    }

    @Test
    void savesToolbarChoicesToUser() {
        navigate(QuotaView.class);

        test(find(ColorSchemeToggleButton.class).single()).click();
        var languageMenu = test(find(LanguageMenuButton.class).single().menu());
        languageMenu.open();
        languageMenu.clickItem("Svenska");
        languageMenu.close();

        assertThat(userAccountService.findSettings(USERNAME)).isEqualTo(new UserSettings("light", "sv"));
    }

    @Test
    void prefersUserSettingsOverCookies() {
        userAccountService.changeSettings(USERNAME, _ -> new UserSettings("dark", "sv"));

        assertThat(colorSchemePreference.resolve(request(new Cookie(ColorSchemePreference.COOKIE, "light"))))
                .isEqualTo(ColorSchemeChoice.DARK);
        assertThat(languagePreference.resolve(request(new Cookie(I18nConfiguration.LOCALE_COOKIE, "en"))))
                .isEqualTo(Locale.of("sv"));
    }

    @Test
    void usesCookiesWhenNotSet() {
        assertThat(colorSchemePreference.resolve(request(new Cookie(ColorSchemePreference.COOKIE, "dark"))))
                .isEqualTo(ColorSchemeChoice.DARK);
        assertThat(languagePreference.resolve(request(new Cookie(I18nConfiguration.LOCALE_COOKIE, "sv"))))
                .isEqualTo(Locale.of("sv"));
    }

    @Test
    @WithAnonymousUser
    void usesCookiesBeforeLogin() {
        userAccountService.changeSettings(USERNAME, _ -> new UserSettings("dark", "sv"));

        assertThat(colorSchemePreference.resolve(request(new Cookie(ColorSchemePreference.COOKIE, "light"))))
                .isEqualTo(ColorSchemeChoice.LIGHT);
    }

    @Test
    @SuppressWarnings("unchecked")
    void editsSettingsInUserEditor() {
        var username = "settings-" + UUID.randomUUID();
        userAccountService.createUser(username, "password1", true, List.of(), null);
        var editor = openEditor(username);

        test((Select<Locale>) find(Select.class, editor).withLabel("Language").single()).selectItem("Svenska");
        test((Select<ColorSchemeChoice>) find(Select.class, editor).withLabel("Appearance").single())
                .selectItem("Dark");
        test(find(Button.class, editor).withText("Save").single()).click();

        assertThat(userAccountService.findSettings(username)).isEqualTo(new UserSettings("dark", "sv"));
        // Another user's settings leave this session alone
        assertThat(UI.getCurrent().getLocale().getLanguage()).isEqualTo("en");
        assertThat(UI.getCurrent().getPage().getColorScheme()).isEqualTo(ColorScheme.Value.LIGHT_DARK);

        // Not set clears them again
        var reopened = openEditor(username);
        ((Select<Locale>) find(Select.class, reopened).withLabel("Language").single()).clear();
        test(find(Button.class, reopened).withText("Save").single()).click();

        assertThat(userAccountService.findSettings(username)).isEqualTo(new UserSettings("dark", null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void appliesOwnSettingsWhenSaved() {
        var editor = openEditor(USERNAME);

        test((Select<Locale>) find(Select.class, editor).withLabel("Language").single()).selectItem("Svenska");
        test((Select<ColorSchemeChoice>) find(Select.class, editor).withLabel("Appearance").single())
                .selectItem("Dark");
        test(find(Button.class, editor).withText("Save").single()).click();

        assertThat(UI.getCurrent().getLocale().getLanguage()).isEqualTo("sv");
        assertThat(UI.getCurrent().getPage().getColorScheme()).isEqualTo(ColorScheme.Value.DARK);
        assertThat(find(ColorSchemeToggleButton.class).single().getIcon().getElement().getAttribute("icon"))
                .isEqualTo("vaadin:moon");

        // Not set stops overriding the browser: the page uses what the cookie or the browser gives, where the cookie
        // now remembers the choice applied above, as a toolbar choice would
        var swedishEditor = find(UserEditor.class).single();
        ((Select<Locale>) find(Select.class, swedishEditor).withLabel("Språk").single()).clear();
        ((Select<ColorSchemeChoice>) find(Select.class, swedishEditor).withLabel("Utseende").single()).clear();
        test(find(Button.class, swedishEditor).withText("Spara").single()).click();

        assertThat(userAccountService.findSettings(USERNAME)).isEqualTo(UserSettings.NONE);
        var request = ((VaadinServletRequest) VaadinRequest.getCurrent()).getHttpServletRequest();
        assertThat(UI.getCurrent().getLocale()).isEqualTo(languagePreference.resolve(request));
        assertThat(UI.getCurrent().getPage().getColorScheme())
                .isEqualTo(colorSchemePreference.resolve(request).value());
    }

    @SuppressWarnings("unchecked")
    private UserEditor openEditor(String username) {
        navigate(UserView.class);
        var grid = test((Grid<UserAccount>) find(Grid.class).single());
        grid.select(IntStream.range(0, grid.size()).filter(row -> grid.getRow(row).getUsername().equals(username))
                .findFirst().orElseThrow());
        return find(UserEditor.class).single();
    }

    private static MockHttpServletRequest request(Cookie... cookies) {
        var request = new MockHttpServletRequest();
        request.setCookies(cookies);
        return request;
    }
}
