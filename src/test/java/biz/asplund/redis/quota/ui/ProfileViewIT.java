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

import biz.asplund.redis.security.UserAccountRepository;
import biz.asplund.redis.security.UserAccountService;
import biz.asplund.redis.security.UserSettings;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.page.ColorScheme;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.router.RouteNotFoundError;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The profile page, where a logged-in user changes their own language, appearance and password.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@WithMockUser(username = ProfileViewIT.USERNAME, roles = "USER")
class ProfileViewIT extends SpringBrowserlessTest {

	static final String USERNAME = "profile-user";
	private static final String PASSWORD = "password1";

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void storeUser() {
		if (!userAccountRepository.existsByUsername(USERNAME)) {
			userAccountService.createUser(USERNAME, PASSWORD, true, List.of("USER"), null);
		}
		reset();
	}

	// Also afterwards, as the browserless setup starts the next test's UI before storeUser runs
	@AfterEach
	void reset() {
		var account = userAccountRepository.findByUsername(USERNAME).orElseThrow();
		userAccountService.updateUser(account.getId(), PASSWORD, true, List.of("USER"), null, UserSettings.NONE,
				"someone-else");
	}

	@Test
	void opensForUserWithoutAdminRole() {
		navigate(ProfileView.class);

		assertThat(find(SideNav.class).single().getItems()).extracting(SideNavItem::getLabel)
				.containsExactly("Home", "My profile");
		navigate("quotas", RouteNotFoundError.class);
	}

	@Test
	@SuppressWarnings("unchecked")
	void savesAndAppliesSettings() {
		navigate(ProfileView.class);

		test((Select<Locale>) find(Select.class).withLabel("Language").single()).selectItem("Svenska");
		test((Select<ColorSchemeChoice>) find(Select.class).withLabel("Appearance").single()).selectItem("Dark");
		test(find(Button.class).withText("Save settings").single()).click();

		assertThat(userAccountService.findSettings(USERNAME)).isEqualTo(new UserSettings("dark", "sv"));
		assertThat(UI.getCurrent().getLocale().getLanguage()).isEqualTo("sv");
		assertThat(UI.getCurrent().getPage().getColorScheme()).isEqualTo(ColorScheme.Value.DARK);
		// The page is rebuilt in Swedish, showing the saved settings
		assertThat(find(Button.class).withText("Spara inställningar").exists()).isTrue();
		assertThat(((Select<Locale>) find(Select.class).withLabel("Språk").single()).getValue())
				.isEqualTo(Locale.of("sv"));
	}

	@Test
	void changesPasswordWithCurrentOne() {
		navigate(ProfileView.class);
		var current = find(PasswordField.class).withLabel("Current password").single();
		var newPassword = find(PasswordField.class).withLabel("New password").single();
		var repeat = find(PasswordField.class).withLabel("Repeat new password").single();
		var change = find(Button.class).withText("Change password").single();

		test(current).setValue("wrong-password");
		test(newPassword).setValue("password2");
		test(repeat).setValue("password2");
		test(change).click();
		assertThat(current.isInvalid()).isTrue();
		assertThat(current.getErrorMessage()).isEqualTo("The current password is not correct");

		test(current).setValue(PASSWORD);
		test(repeat).setValue("password3");
		test(change).click();
		assertThat(repeat.isInvalid()).isTrue();
		assertThat(repeat.getErrorMessage()).isEqualTo("The passwords are not the same");

		test(newPassword).setValue("short");
		test(repeat).setValue("short");
		test(change).click();
		assertThat(newPassword.isInvalid()).isTrue();
		assertThat(newPassword.getErrorMessage()).isEqualTo("Enter a password of at least 8 characters");

		test(newPassword).setValue("password2");
		test(repeat).setValue("password2");
		test(change).click();

		var stored = userAccountRepository.findByUsername(USERNAME).orElseThrow().getPassword();
		assertThat(passwordEncoder.matches("password2", stored)).isTrue();
		assertThat(current.getValue()).isEmpty();
	}
}
