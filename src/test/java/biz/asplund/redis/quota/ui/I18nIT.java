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
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@WithMockUser(username = "admin", roles = "ADMIN")
class I18nIT extends SpringBrowserlessTest {

	private static final Locale SWEDISH = Locale.of("sv");

	@Test
	void usesEnglishByDefault() {
		navigate(QuotaView.class);

		assertThat(UI.getCurrent().getLocale().getLanguage()).isEqualTo("en");
		assertThat(UI.getCurrent().getInternals().getTitle()).isEqualTo("Quotas");
		assertThat(find(Button.class).withText("New quota").exists()).isTrue();
	}

	@Test
	void switchesQuotasViewToSwedishInPlace() {
		var view = navigate(QuotaView.class);

		pickLanguage("Svenska");

		assertThat(VaadinSession.getCurrent().getLocale()).isEqualTo(SWEDISH);
		assertThat(getCurrentView()).isSameAs(view);
		assertThat(UI.getCurrent().getInternals().getTitle()).isEqualTo("Kvoter");
		assertThat(find(H1.class).single().getText()).isEqualTo("Kvoter");
		assertThat(find(SideNav.class).single().getItems()).extracting(SideNavItem::getLabel).containsExactly("Kvoter");
		assertThat(find(TextField.class).withPlaceholder("Filtrera på id").exists()).isTrue();
		assertThat(find(Button.class).withText("Ny kvot").exists()).isTrue();
		var columns = grid().getColumns();
		assertThat(columns.get(1).getHeaderText()).isEqualTo("Timme");
		assertThat(columns.getLast().getHeaderText()).isEqualTo("Senast använd");

		pickLanguage("English");

		assertThat(find(H1.class).single().getText()).isEqualTo("Quotas");
		assertThat(find(Button.class).withText("New quota").exists()).isTrue();
	}

	@Test
	void rebuildsOpenEditorInNewLanguage() {
		navigate(QuotaView.class);
		test(find(Button.class).withText("New quota").single()).click();

		pickLanguage("Svenska");

		var editor = find(QuotaEditor.class).single();
		assertThat(find(IntegerField.class, editor).withLabel("Gräns per timme").exists()).isTrue();
		assertThat(find(Button.class, editor).withText("Spara").exists()).isTrue();
	}

	@Test
	void switchesLoginFormToSwedish() {
		navigate(LoginView.class);

		pickLanguage("Svenska");

		// The form sends its texts to the browser as the i18n property
		var i18n = String.valueOf(find(LoginForm.class).single().getElement().getPropertyRaw("i18n"));
		assertThat(i18n).contains("Logga in", "Användarnamn", "Lösenord", "Fel användarnamn eller lösenord");
		assertThat(UI.getCurrent().getInternals().getTitle()).isEqualTo("Logga in");
	}

	@Test
	void offersEachLanguageInItsOwnName() {
		navigate(QuotaView.class);

		var select = test(find(LanguageSelect.class).single());
		assertThat(select.getSuggestions()).containsExactly("English", "Svenska");
		assertThat(select.getSelected()).isEqualTo(Locale.ENGLISH);
	}

	@Test
	void translatesEveryKey() throws IOException {
		assertThat(translations("translations_sv").stringPropertyNames())
				.isEqualTo(translations("translations").stringPropertyNames());
	}

	@SuppressWarnings("unchecked")
	private Grid<QuotaRow> grid() {
		return find(Grid.class).single();
	}

	private void pickLanguage(String language) {
		test(find(LanguageSelect.class).single()).selectItem(language);
	}

	private static Properties translations(String name) throws IOException {
		var properties = new Properties();
		try (var in = I18nIT.class.getResourceAsStream("/vaadin-i18n/" + name + ".properties")) {
			properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		return properties;
	}
}
