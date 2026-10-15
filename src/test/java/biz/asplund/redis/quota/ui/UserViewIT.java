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
import biz.asplund.redis.security.Role;
import biz.asplund.redis.security.UserAccount;
import biz.asplund.redis.security.UserAccountRepository;
import biz.asplund.redis.security.UserAccountService;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridTester;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.RouteNotFoundError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@WithMockUser(username = UserViewIT.SELF, roles = "ADMIN")
class UserViewIT extends SpringBrowserlessTest {

	// The logged-in user, stored so the view can show its own account
	static final String SELF = "user-view-admin";

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@BeforeEach
	void storeLoggedInUser() {
		if (!userAccountRepository.existsByUsername(SELF)) {
			userAccountService.createUser(SELF, "password1", true, List.of("ADMIN"));
		}
	}

	@Test
	@WithMockUser(roles = "USER")
	void deniesUserWithoutAdminRole() {
		navigate("users", RouteNotFoundError.class);
	}

	@Test
	void listsUsersWithRolesAndStatus() {
		var role = userAccountService.createRole(uniqueRoleName());
		var account = userAccountService.createUser(uniqueName(), "password1", false, List.of("ADMIN", role.getName()));
		navigate(UserView.class);

		var grid = grid();
		var row = rowOf(grid, account.getUsername());
		assertThat(grid.getCellText(row, 1)).isEqualTo(String.join(", ", List.of("ADMIN", role.getName()).stream()
				.sorted().toList()));
		assertThat(grid.getCellComponent(row, 2).getElement().getText()).isEqualTo("Disabled");
	}

	@Test
	void createsUser() {
		navigate(UserView.class);
		test(find(Button.class).withText("New user").single()).click();
		var editor = find(UserEditor.class).single();
		var username = uniqueName();

		test(find(TextField.class, editor).withLabel("Username").single()).setValue(username);
		test(find(PasswordField.class, editor).withLabel("Password").single()).setValue("password1");
		rolesField(editor).setValue(Set.of("ADMIN"));
		test(find(Button.class, editor).withText("Save").single()).click();

		var account = userAccountRepository.findByUsername(username).orElseThrow();
		assertThat(account.isEnabled()).isTrue();
		assertThat(account.getRoles()).extracting(Role::getName).containsExactly("ADMIN");
		assertThat(notifications()).contains("User " + username + " saved");
		// The editor stays open on the saved user
		assertThat(find(UserEditor.class).single().userId()).isEqualTo(account.getId());
	}

	@Test
	void showsWhyNewUserIsInvalid() {
		navigate(UserView.class);
		test(find(Button.class).withText("New user").single()).click();
		var editor = find(UserEditor.class).single();
		var username = find(TextField.class, editor).withLabel("Username").single();
		var password = find(PasswordField.class, editor).withLabel("Password").single();

		test(username).setValue(SELF);
		test(password).setValue("short");
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(username.isInvalid()).isTrue();
		assertThat(username.getErrorMessage()).isEqualTo("A user with this username already exists");

		test(username).setValue(uniqueName());
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(password.isInvalid()).isTrue();
		assertThat(password.getErrorMessage()).isEqualTo("Enter a password of at least 8 characters");
	}

	@Test
	void editsRolesAndDisablesUser() {
		var role = userAccountService.createRole(uniqueRoleName());
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of("ADMIN"));
		var editor = openEditor(account.getUsername());

		var enabled = find(Checkbox.class, editor).withLabel("Can log in").single();
		assertThat(enabled.getValue()).isTrue();
		test(enabled).click();
		rolesField(editor).setValue(Set.of(role.getName()));
		test(find(Button.class, editor).withText("Save").single()).click();

		var saved = userAccountRepository.findByUsername(account.getUsername()).orElseThrow();
		assertThat(saved.isEnabled()).isFalse();
		assertThat(saved.getRoles()).extracting(Role::getName).containsExactly(role.getName());
		assertThat(saved.getPassword()).isEqualTo(account.getPassword());
	}

	@Test
	void keepsOwnAccountAnEnabledAdmin() {
		var editor = openEditor(SELF);

		assertThat(find(Checkbox.class, editor).withLabel("Can log in").single().isEnabled()).isFalse();
		assertThat(find(Button.class, editor).withText("Delete").exists()).isFalse();

		var roles = rolesField(editor);
		roles.setValue(Set.of());
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(roles.isInvalid()).isTrue();
		assertThat(roles.getErrorMessage()).isEqualTo("You cannot remove the ADMIN role from your own account");
		assertThat(userAccountRepository.findByUsername(SELF).orElseThrow().getRoles())
				.extracting(Role::getName).contains("ADMIN");
	}

	@Test
	void deletesUserAfterConfirmation() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of());
		var editor = openEditor(account.getUsername());

		test(find(Button.class, editor).withText("Delete").single()).click();
		var dialog = test(find(ConfirmDialog.class).single());
		assertThat(dialog.getHeader()).isEqualTo("Delete user " + account.getUsername() + "?");
		dialog.confirm();

		assertThat(userAccountRepository.existsByUsername(account.getUsername())).isFalse();
		assertThat(find(UserEditor.class).exists()).isFalse();
		var grid = grid();
		assertThat(IntStream.range(0, grid.size()).mapToObj(grid::getRow))
				.extracting(UserAccount::getUsername).doesNotContain(account.getUsername());
	}

	@Test
	void addsAndDeletesRoles() {
		navigate(UserView.class);
		test(find(Button.class).withText("Roles").single()).click();
		var dialog = find(RolesDialog.class).single();
		var name = find(TextField.class, dialog).withAriaLabel("New role").single();

		test(name).setValue("ROLE_X");
		test(find(Button.class, dialog).withText("Add").single()).click();
		assertThat(name.isInvalid()).isTrue();
		assertThat(name.getErrorMessage()).isEqualTo("Use letters A-Z, digits and _, starting with a letter, without ROLE_");

		var roleName = uniqueRoleName();
		test(name).setValue(roleName.toLowerCase(Locale.ROOT));
		test(find(Button.class, dialog).withText("Add").single()).click();

		assertThat(userAccountService.findAllRoles()).extracting(Role::getName).contains(roleName);
		assertThat(deleteRoleButton(dialog, "ADMIN").isEnabled()).isFalse();

		test(deleteRoleButton(dialog, roleName)).click();
		var confirm = test(find(ConfirmDialog.class).single());
		assertThat(confirm.getHeader()).isEqualTo("Delete role " + roleName + "?");
		confirm.confirm();

		assertThat(userAccountService.findAllRoles()).extracting(Role::getName).doesNotContain(roleName);
	}

	// The delete buttons are rendered in the grid cells, so they are not in the component tree
	@SuppressWarnings("unchecked")
	private Button deleteRoleButton(RolesDialog dialog, String roleName) {
		var grid = test((Grid<Role>) find(Grid.class, dialog).single());
		var row = IntStream.range(0, grid.size()).filter(index -> grid.getRow(index).getName().equals(roleName))
				.findFirst().orElseThrow(() -> new AssertionError("No row for " + roleName));
		var button = (Button) grid.getCellComponent(row, 2);
		assertThat(button.getAriaLabel()).contains("Delete role " + roleName);
		return button;
	}

	private UserEditor openEditor(String username) {
		navigate(UserView.class);
		var grid = grid();
		grid.select(rowOf(grid, username));
		return find(UserEditor.class).single();
	}

	@SuppressWarnings("unchecked")
	private MultiSelectComboBox<String> rolesField(UserEditor editor) {
		return find(MultiSelectComboBox.class, editor).withLabel("Roles").single();
	}

	@SuppressWarnings("unchecked")
	private GridTester<Grid<UserAccount>, UserAccount> grid() {
		return test((Grid<UserAccount>) find(Grid.class).single());
	}

	private static int rowOf(GridTester<Grid<UserAccount>, UserAccount> grid, String username) {
		return IntStream.range(0, grid.size())
				.filter(row -> grid.getRow(row).getUsername().equals(username))
				.findFirst().orElseThrow(() -> new AssertionError("No row for " + username));
	}

	private List<String> notifications() {
		return find(Notification.class).all().stream().map(notification -> test(notification).getText()).toList();
	}

	private static String uniqueName() {
		return "user-" + UUID.randomUUID();
	}

	private static String uniqueRoleName() {
		return "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
	}
}
