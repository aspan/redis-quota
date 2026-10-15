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
import biz.asplund.redis.quota.QuotaDefinition;
import biz.asplund.redis.quota.QuotaDefinitionRepository;
import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.redis.security.QuotaUsageRecord;
import biz.asplund.redis.security.QuotaUsageRecordRepository;
import biz.asplund.redis.security.Role;
import biz.asplund.redis.security.UserAccount;
import biz.asplund.redis.security.UserAccountRepository;
import biz.asplund.redis.security.UserAccountService;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.MultiSelectComboBox;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridTester;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.select.SelectTester;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.RouteNotFoundError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.vaadin.addons.chartjs.config.BarChartConfig;
import org.vaadin.addons.chartjs.data.BarDataset;
import org.vaadin.addons.chartjs.data.LineDataset;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
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

	@Autowired
	private QuotaDefinitionRepository quotaDefinitionRepository;

	@Autowired
	private QuotaManager quotaManager;

	@Autowired
	private QuotaUsageRecordRepository quotaUsageRecordRepository;

	@BeforeEach
	void storeLoggedInUser() {
		if (!userAccountRepository.existsByUsername(SELF)) {
			userAccountService.createUser(SELF, "password1", true, List.of("ADMIN"), null);
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
		var account = userAccountService.createUser(uniqueName(), "password1", false,
				List.of("ADMIN", role.getName()), null);
		navigate(UserView.class);

		var grid = grid();
		var row = rowOf(grid, account.getUsername());
		assertThat(grid.getCellText(row, 3)).isEqualTo(String.join(", ", List.of("ADMIN", role.getName()).stream()
				.sorted().toList()));
		assertThat(grid.getCellText(row, 4)).isEqualTo("No quota");
		assertThat(grid.getCellComponent(row, 5).getElement().getText()).isEqualTo("Disabled");
	}

	@Test
	void createsUser() {
		navigate(UserView.class);
		test(find(Button.class).withText("New user").single()).click();
		var editor = find(UserEditor.class).single();
		var username = uniqueName();

		test(find(TextField.class, editor).withLabel("Username").single()).setValue(username);
		test(find(PasswordField.class, editor).withLabel("Password").single()).setValue("password1");
		rolesField(editor).setValue(roles("ADMIN"));
		test(find(Button.class, editor).withText("Save").single()).click();

		var account = userAccountRepository.findByUsername(username).orElseThrow();
		assertThat(account.isEnabled()).isTrue();
		assertThat(account.getRoles()).extracting(Role::getName).containsExactly("ADMIN");
		assertThat(notifications()).contains("User " + username + " saved");
		// The editor stays open on the saved user
		assertThat(find(UserEditor.class).single().userId()).isEqualTo(account.getId());
	}

	@Test
	void setsAndFiltersByName() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(), null);
		var editor = openEditor(account.getUsername());

		test(find(TextField.class, editor).withLabel("Name").single()).setValue("Zelda " + account.getUsername());
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(userAccountService.findName(account.getUsername())).isEqualTo("Zelda " + account.getUsername());
		test(find(TextField.class).withPlaceholder("Filter by username, name or email").single()).setValue("zelda");
		var grid = grid();
		assertThat(IntStream.range(0, grid.size()).mapToObj(grid::getRow)).extracting(UserAccount::getUsername)
				.contains(account.getUsername()).doesNotContain(SELF);
		assertThat(grid.getCellText(rowOf(grid, account.getUsername()), 1)).isEqualTo("Zelda " + account.getUsername());
	}

	@Test
	void showsWhyNewUserIsInvalid() {
		navigate(UserView.class);
		test(find(Button.class).withText("New user").single()).click();
		var editor = find(UserEditor.class).single();
		var username = find(TextField.class, editor).withLabel("Username").single();
		var password = find(PasswordField.class, editor).withLabel("Password").single();

		test(find(Button.class, editor).withText("Save").single()).click();

		// The binder checks the constraints on UserAccount and the password before the service is called
		assertThat(username.isInvalid()).isTrue();
		assertThat(username.getErrorMessage()).isEqualTo("Username is required");
		assertThat(password.isInvalid()).isTrue();
		assertThat(password.getErrorMessage()).isEqualTo("Enter a password of at least 8 characters");

		// A username already taken is only found by the service
		test(username).setValue(SELF);
		test(password).setValue("password1");
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(username.isInvalid()).isTrue();
		assertThat(username.getErrorMessage()).isEqualTo("A user already has this username, or this email address");
	}

	@Test
	void editsRolesAndDisablesUser() {
		var role = userAccountService.createRole(uniqueRoleName());
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of("ADMIN"), null);
		var editor = openEditor(account.getUsername());

		var enabled = find(Checkbox.class, editor).withLabel("Can log in").single();
		assertThat(enabled.getValue()).isTrue();
		test(enabled).click();
		rolesField(editor).setValue(roles(role.getName()));
		test(find(Button.class, editor).withText("Save").single()).click();

		var saved = userAccountRepository.findByUsername(account.getUsername()).orElseThrow();
		assertThat(saved.isEnabled()).isFalse();
		assertThat(saved.getRoles()).extracting(Role::getName).containsExactly(role.getName());
		assertThat(saved.getPassword()).isEqualTo(account.getPassword());
	}

	@Test
	void setsQuotaOfUser() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(), null);
		var editor = openEditor(account.getUsername());

		var limit = find(IntegerField.class, editor).withLabel("Limit").single();
		assertThat(limit.isEnabled()).isFalse();
		quotaPeriodField(editor).selectItem("Daily");
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(limit.isInvalid()).isTrue();
		assertThat(limit.getErrorMessage()).isEqualTo("Enter a limit of 0 or more");

		test(limit).setValue(100);
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(quotaDefinitionRepository.findById(account.getUsername()))
				.contains(new QuotaDefinition(account.getUsername(), QuotaPeriod.DAILY, 100));
		var grid = grid();
		assertThat(grid.getCellText(rowOf(grid, account.getUsername()), 4)).isEqualTo("100 / Daily");
	}

	@Test
	@SuppressWarnings("unchecked")
	void showsUserQuotaReadOnlyInQuotasView() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				new QuotaConfig(100, QuotaPeriod.DAILY));
		navigate(QuotaView.class);
		var grid = test((Grid<QuotaRow>) find(Grid.class).single());
		grid.select(IntStream.range(0, grid.size())
				.filter(row -> grid.getRow(row).id().equals(account.getUsername())).findFirst().orElseThrow());
		var editor = find(QuotaEditor.class).single();

		assertThat(find(Select.class, editor).withLabel("Period").single().isReadOnly()).isTrue();
		var limit = find(IntegerField.class, editor).withLabel("Limit").single();
		assertThat(limit.isReadOnly()).isTrue();
		assertThat(limit.getHelperText()).isEqualTo("This is a user's quota; change it in the Users view");
		assertThat(find(Button.class, editor).withText("Save").exists()).isFalse();
		assertThat(find(Button.class, editor).withText("Delete").exists()).isFalse();
		// Usage can still be changed there
		assertThat(find(Button.class, editor).withText("Reset").exists()).isTrue();
	}

	@Test
	void showsAndChangesUsageOfUserQuota() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				new QuotaConfig(10, QuotaPeriod.DAILY));
		var username = account.getUsername();
		quotaManager.checkAndIncrement(username, 3);
		var editor = openEditor(username);

		var card = find(QuotaUsageCard.class, editor).single();
		assertThat(card.getTitleAsText()).isEqualTo("Daily");
		assertThat(card.getSubtitle().getElement().getText()).isEqualTo("3 of 10 used, 7 left");
		// Used just now, so not never
		assertThat(find(Div.class, card).withText("Last used Never").exists()).isFalse();
		assertThat(find(Div.class, card).withCondition(div -> div.getText().startsWith("Last used ")).exists()).isTrue();

		test(find(Button.class, card).withText("Reset").single()).click();

		assertThat(quotaManager.getUsage(username)).isZero();
		// The editor is rebuilt with the new usage
		var refreshed = find(QuotaUsageCard.class, find(UserEditor.class).single()).single();
		assertThat(refreshed.getSubtitle().getElement().getText()).isEqualTo("0 of 10 used, 10 left");
	}

	@Test
	void showsUsageHistoryAsBarChart() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				new QuotaConfig(10, QuotaPeriod.DAILY));
		quotaUsageRecordRepository.saveAll(List.of(
				usageRecord(account, LocalDate.of(2026, 10, 12), 3),
				usageRecord(account, LocalDate.of(2026, 10, 13), 10)));

		var chart = find(UsageHistoryChart.class, openEditor(account.getUsername())).single();

		// Oldest on the left, the used-up day in red, and the limit as a line
		var data = ((BarChartConfig) chart.getConfig()).data();
		assertThat(data.getLabels()).containsExactly("Oct 12, 2026", "Oct 13, 2026");
		assertThat(data.getDatasets()).hasSize(2);
		assertThat(((BarDataset) data.getDatasetAtIndex(0)).getData()).containsExactly(3.0, 10.0);
		assertThat(((LineDataset) data.getDatasetAtIndex(1)).getData()).containsExactly(10.0, 10.0);
	}

	@Test
	void tellsWhenNoHistoryIsRecorded() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				new QuotaConfig(10, QuotaPeriod.DAILY));

		var editor = openEditor(account.getUsername());

		assertThat(find(UsageHistoryChart.class, editor).exists()).isFalse();
		assertThat(find(Span.class, editor).withText("No finished periods recorded yet").exists()).isTrue();
	}

	private static QuotaUsageRecord usageRecord(UserAccount account, LocalDate day, int used) {
		var record = new QuotaUsageRecord(account, QuotaPeriod.DAILY, day.atStartOfDay(ZoneId.systemDefault())
				.toInstant());
		record.update(used, 10);
		return record;
	}

	@Test
	void showsNoUsageWithoutQuota() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(), null);

		var editor = openEditor(account.getUsername());

		assertThat(find(QuotaUsageCard.class, editor).exists()).isFalse();
		assertThat(find(UsageHistoryChart.class, editor).exists()).isFalse();
	}

	@Test
	void tellsWhenQuotaIsMissingInRedis() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				new QuotaConfig(10, QuotaPeriod.DAILY));
		quotaDefinitionRepository.deleteById(account.getUsername());

		var editor = openEditor(account.getUsername());

		assertThat(find(QuotaUsageCard.class, editor).exists()).isFalse();
		assertThat(find(Span.class, editor)
				.withText("The quota is missing in Redis. Save the user to copy it there again.").exists()).isTrue();
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
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(), null);
		var editor = openEditor(account.getUsername());

		test(find(Button.class, editor).withText("Delete").single()).click();
		var dialog = test(find(ConfirmDialog.class).single());
		assertThat(dialog.getHeader()).isEqualTo("Delete user " + account.getUsername() + "?");
		assertThat(dialog.getText()).contains("their quota, usage and usage history are deleted too");
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
		assertThat(deleteRoleButton(dialog, "USER").isEnabled()).isFalse();

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
	private SelectTester<Select<QuotaPeriod>, QuotaPeriod> quotaPeriodField(UserEditor editor) {
		return test((Select<QuotaPeriod>) find(Select.class, editor).withLabel("Period").single());
	}

	// Roles are equal by name, so a new Role with the name selects the stored one
	private static Set<Role> roles(String... names) {
		return Arrays.stream(names).map(name -> {
			var role = new Role();
			role.setName(name);
			return role;
		}).collect(Collectors.toSet());
	}

	@SuppressWarnings("unchecked")
	private MultiSelectComboBox<Role> rolesField(UserEditor editor) {
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
