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

import biz.asplund.redis.quota.QuotaConfig;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.redis.security.UserAccountService;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.i18n.I18NProvider;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.ResourceBundle;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
// A user that is not stored, so choices are kept in cookies only and tests do not change each other's settings;
// UserSettingsIT covers the settings of a stored user
@WithMockUser(username = "not-a-stored-user", roles = "ADMIN")
class I18nIT extends SpringBrowserlessTest {

	@Autowired
	private I18nProperties i18nProperties;

	@Autowired
	private I18NProvider i18nProvider;

	@Autowired
	private UserAccountService userAccountService;

	private static final Locale SWEDISH = Locale.of("sv");

	@Test
	void usesEnglishByDefault() {
		navigate(QuotaView.class);

		assertThat(UI.getCurrent().getLocale().getLanguage()).isEqualTo("en");
		assertThat(UI.getCurrent().getInternals().getTitle()).isEqualTo("Quotas");
		assertThat(find(TextField.class).withPlaceholder("Filter by id").exists()).isTrue();
	}

	@Test
	void switchesQuotasViewToSwedishInPlace() {
		var view = navigate(QuotaView.class);

		pickLanguage("Svenska");

		assertThat(VaadinSession.getCurrent().getLocale()).isEqualTo(SWEDISH);
		assertThat(getCurrentView()).isSameAs(view);
		assertThat(UI.getCurrent().getInternals().getTitle()).isEqualTo("Kvoter");
		assertThat(find(H1.class).single().getText()).isEqualTo("Kvoter");
		assertThat(find(SideNav.class).single().getItems()).extracting(SideNavItem::getLabel)
				.containsExactly("Hem", "Skicka sms", "Kvoter", "Användare", "Min profil");
		assertThat(find(TextField.class).withPlaceholder("Filtrera på id").exists()).isTrue();
		var columns = grid().getColumns();
		assertThat(columns.get(1).getHeaderText()).isEqualTo("Period");
		assertThat(columns.get(2).getHeaderText()).isEqualTo("Förbrukning");
		assertThat(columns.getLast().getHeaderText()).isEqualTo("Senast använd");
		assertThat(((LanguageFlag) find(LanguageMenuButton.class).single().getIcon()).getSrc())
				.isEqualTo(LanguageFlag.PATH + "se.svg");

		pickLanguage("English");

		assertThat(find(H1.class).single().getText()).isEqualTo("Quotas");
		assertThat(find(TextField.class).withPlaceholder("Filter by id").exists()).isTrue();
	}

	@Test
	void rebuildsOpenEditorInNewLanguage() {
		openEditor();

		pickLanguage("Svenska");

		var editor = find(QuotaEditor.class).single();
		assertThat(find(IntegerField.class, editor).withLabel("Gräns").exists()).isTrue();
		assertThat(find(Button.class, editor).withText("Nollställ").exists()).isTrue();
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

		var button = find(LanguageMenuButton.class).single();
		var menu = test(button.menu());
		menu.open();
		assertThat(menu.getItemTexts()).containsExactly("English", "Svenska");
		assertThat(menu.isItemChecked("English")).isTrue();
		assertThat(menu.isItemChecked("Svenska")).isFalse();
		menu.close();
		// The button shows the current language's flag rather than a code
		assertThat(button.getText()).isEmpty();
		var flag = (LanguageFlag) button.getIcon();
		assertThat(flag.getSrc()).isEqualTo(LanguageFlag.PATH + "gb.svg");
		assertThat(flag.getHeight()).isEqualTo(MainLayout.TOOLBAR_ICON_SIZE);
	}

	@Test
	void translatesEveryKey() throws IOException {
		assertThat(translations("translations_sv").stringPropertyNames())
				.isEqualTo(translations("translations").stringPropertyNames());
	}

	@Test
	void translatesEveryValidationMessage() throws IOException {
		assertThat(properties("/ValidationMessages_sv.properties").stringPropertyNames())
				.isEqualTo(properties("/ValidationMessages.properties").stringPropertyNames());
		assertThat(properties("/ValidationMessages_en.properties")).isEmpty();
	}

	@Test
	@SuppressWarnings("unchecked")
	void showsValidationMessagesInUiLanguage() {
		navigate(UserView.class);
		pickLanguage("Svenska");
		test(find(Button.class).withText("Ny användare").single()).click();

		// A negative limit breaks @PositiveOrZero on UserAccount
		var editor = find(UserEditor.class).single();
		test((Select<QuotaPeriod>) find(Select.class, editor).withLabel("Period").single()).selectItem("Dag");
		var limit = find(IntegerField.class, editor).withLabel("Gräns").single();
		test(limit).setValue(-1);
		test(find(Button.class, editor).withText("Spara").single()).click();

		assertThat(limit.isInvalid()).isTrue();
		assertThat(limit.getErrorMessage()).isEqualTo("Ange en gräns på 0 eller mer");
	}

	// Opens the editor of a new user's quota, as quotas belong to users
	private void openEditor() {
		var id = "i18n-" + UUID.randomUUID();
		userAccountService.createUser(id, "password1", true, List.of(), new QuotaConfig(10, QuotaPeriod.DAILY));
		navigate(QuotaView.class);
		var grid = test(grid());
		grid.select(IntStream.range(0, grid.size()).filter(row -> grid.getRow(row).id().equals(id))
				.findFirst().orElseThrow());
	}

	@SuppressWarnings("unchecked")
	private Grid<QuotaRow> grid() {
		return find(Grid.class).single();
	}

	private void pickLanguage(String language) {
		var menu = test(find(LanguageMenuButton.class).single().menu());
		menu.open();
		menu.clickItem(language);
		menu.close();
	}

	@Test
	void hasTranslationsForEveryConfiguredLocale() {
		assertThat(i18nProperties.locales()).allSatisfy(locale ->
				assertThat(I18nIT.class.getResource("/vaadin-i18n/translations_" + locale + ".properties"))
						.as("translations_%s.properties", locale).isNotNull());
	}

	@Test
	void servesEnglishWhenJvmDefaultLocaleIsSwedish() {
		var defaultLocale = Locale.getDefault();
		try {
			Locale.setDefault(SWEDISH);
			ResourceBundle.clearCache();

			assertThat(i18nProvider.getTranslation("quotas.title", Locale.ENGLISH)).isEqualTo("Quotas");
			assertThat(i18nProvider.getTranslation("quotas.title", SWEDISH)).isEqualTo("Kvoter");
		} finally {
			Locale.setDefault(defaultLocale);
			ResourceBundle.clearCache();
		}
	}

	private static Properties translations(String name) throws IOException {
		return properties("/vaadin-i18n/" + name + ".properties");
	}

	private static Properties properties(String path) throws IOException {
		var properties = new Properties();
		try (var in = I18nIT.class.getResourceAsStream(path)) {
			properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
		}
		return properties;
	}
}
