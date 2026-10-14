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

import biz.asplund.redis.TestcontainersConfiguration;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.contextmenu.ContextMenu;
import com.vaadin.flow.component.contextmenu.ContextMenuTester;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.server.VaadinSession;
import jakarta.servlet.http.Cookie;
import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@WithMockUser(username = "admin", roles = "ADMIN")
class ColorSchemeIT extends SpringBrowserlessTest {

	@Autowired
	private ColorSchemePreference colorSchemePreference;

	@Test
	void followsSystemByDefault() {
		navigate(QuotaView.class);

		assertThat(isChecked("System")).isTrue();
		assertThat(UI.getCurrent().getPage().getColorScheme()).isEqualTo(ColorScheme.Value.LIGHT_DARK);
	}

	@Test
	void switchesPageToPickedScheme() {
		navigate(QuotaView.class);

		pick("Dark");

		assertThat(UI.getCurrent().getPage().getColorScheme()).isEqualTo(ColorScheme.Value.DARK);
		assertThat(VaadinSession.getCurrent().getAttribute(ColorSchemeChoice.class)).isEqualTo(ColorSchemeChoice.DARK);
		assertThat(((Icon) find(ColorSchemeMenuButton.class).single().getIcon()).getIcon()).isEqualTo("vaadin:moon");
		assertThat(isChecked("Dark")).isTrue();
		assertThat(isChecked("System")).isFalse();

		pick("Light");

		assertThat(UI.getCurrent().getPage().getColorScheme()).isEqualTo(ColorScheme.Value.LIGHT);
	}

	@Test
	void keepsChoiceAcrossViews() {
		navigate(QuotaView.class);
		pick("Dark");

		navigate(LoginView.class);

		assertThat(isChecked("Dark")).isTrue();
	}

	@Test
	void translatesChoices() {
		navigate(QuotaView.class);
		var languageMenu = test(find(LanguageMenuButton.class).single().menu());
		languageMenu.open();
		languageMenu.clickItem("Svenska");
		languageMenu.close();

		assertThat(itemTexts()).containsExactly("System", "Ljust", "Mörkt");
		assertThat(find(ColorSchemeMenuButton.class).single().getAriaLabel()).contains("Utseende");
	}

	@Test
	void readsChoiceFromCookie() {
		assertThat(colorSchemePreference.resolve(request(new Cookie(ColorSchemePreference.COOKIE, "dark"))))
				.isEqualTo(ColorSchemeChoice.DARK);
		assertThat(colorSchemePreference.resolve(request(new Cookie(ColorSchemePreference.COOKIE, "sepia"))))
				.isEqualTo(ColorSchemeChoice.SYSTEM);
		assertThat(colorSchemePreference.resolve(request())).isEqualTo(ColorSchemeChoice.SYSTEM);
	}

	@Test
	void setsSchemeInFirstPage() {
		var document = Jsoup.parse(
				"<html theme=\"light dark\" style=\"--accent: red; color-scheme: light dark;\"><head></head><body></body></html>");

		ColorSchemePreference.apply(document, ColorSchemeChoice.DARK);

		var html = document.selectFirst("html");
		assertThat(html.attr("theme")).isEqualTo(ColorScheme.Value.DARK.getThemeValue());
		assertThat(html.attr("style")).isEqualTo("--accent: red; color-scheme: dark;");
	}

	private void pick(String choice) {
		var menu = menu();
		menu.open();
		menu.clickItem(choice);
		menu.close();
	}

	// Menu items can only be read while the menu is open, as in the browser
	private boolean isChecked(String choice) {
		var menu = menu();
		menu.open();
		var checked = menu.isItemChecked(choice);
		menu.close();
		return checked;
	}

	private List<String> itemTexts() {
		var menu = menu();
		menu.open();
		var texts = menu.getItemTexts();
		menu.close();
		return texts;
	}

	private ContextMenuTester<ContextMenu> menu() {
		return test(find(ColorSchemeMenuButton.class).single().menu());
	}

	private static MockHttpServletRequest request(Cookie... cookies) {
		var request = new MockHttpServletRequest();
		if (cookies.length > 0) {
			request.setCookies(cookies);
		}
		return request;
	}
}
