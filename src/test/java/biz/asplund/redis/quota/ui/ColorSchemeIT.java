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

		assertThat(test(find(ColorSchemeSelect.class).single()).getSelected()).isEqualTo(ColorSchemeChoice.SYSTEM);
		assertThat(UI.getCurrent().getPage().getColorScheme()).isEqualTo(ColorScheme.Value.LIGHT_DARK);
	}

	@Test
	void switchesPageToPickedScheme() {
		navigate(QuotaView.class);

		pick("Dark");

		assertThat(UI.getCurrent().getPage().getColorScheme()).isEqualTo(ColorScheme.Value.DARK);
		assertThat(VaadinSession.getCurrent().getAttribute(ColorSchemeChoice.class)).isEqualTo(ColorSchemeChoice.DARK);
		assertThat(((Icon) find(ColorSchemeSelect.class).single().getPrefixComponent()).getIcon()).isEqualTo("vaadin:moon");

		pick("Light");

		assertThat(UI.getCurrent().getPage().getColorScheme()).isEqualTo(ColorScheme.Value.LIGHT);
	}

	@Test
	void keepsChoiceAcrossViews() {
		navigate(QuotaView.class);
		pick("Dark");

		navigate(LoginView.class);

		assertThat(test(find(ColorSchemeSelect.class).single()).getSelected()).isEqualTo(ColorSchemeChoice.DARK);
	}

	@Test
	void translatesChoices() {
		navigate(QuotaView.class);
		test(find(LanguageSelect.class).single()).selectItem("Svenska");

		assertThat(test(find(ColorSchemeSelect.class).single()).getSuggestions()).containsExactly("System", "Ljust", "Mörkt");
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
		test(find(ColorSchemeSelect.class).single()).selectItem(choice);
	}

	private static MockHttpServletRequest request(Cookie... cookies) {
		var request = new MockHttpServletRequest();
		if (cookies.length > 0) {
			request.setCookies(cookies);
		}
		return request;
	}
}
