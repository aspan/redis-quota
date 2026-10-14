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
import biz.asplund.redis.quota.QuotaDefinition;
import biz.asplund.redis.quota.QuotaDefinitionRepository;
import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridTester;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.RouteNotFoundError;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// Browserless tests of the admin UI against a real Redis; each test uses its own quota ids
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class QuotaViewIT extends SpringBrowserlessTest {

	@Autowired
	private QuotaDefinitionRepository quotaDefinitionRepository;

	@Autowired
	private QuotaManager quotaManager;

	@Test
	@WithAnonymousUser
	void redirectsAnonymousUserToLogin() {
		navigate("", LoginView.class);
	}

	@Test
	@WithMockUser(roles = "USER")
	void deniesUserWithoutAdminRole() {
		navigate("", RouteNotFoundError.class);
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void showsQuotasViewInLayoutWithMenu() {
		navigate(QuotaView.class);

		assertThat(find(H1.class).single().getText()).isEqualTo("Quotas");
		assertThat(find(SideNav.class).single().getItems())
				.extracting(SideNavItem::getLabel, SideNavItem::getPath)
				.containsExactly(tuple("Quotas", ""));
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void listsDefinitionsWithUsage() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.HOURLY, 10, QuotaPeriod.DAILY, 100)));
		quotaManager.setUsage(id, QuotaPeriod.HOURLY, 10);
		quotaManager.setUsage(id, QuotaPeriod.DAILY, 85);

		navigate(QuotaView.class);
		var grid = grid();
		var row = rowOf(grid, id);

		var hourly = (Badge) grid.getCellComponent(row, 1);
		assertThat(hourly.getText()).isEqualTo("10 / 10");
		assertThat(hourly.getThemeNames()).contains(BadgeVariant.ERROR.getVariantName());
		var daily = (Badge) grid.getCellComponent(row, 2);
		assertThat(daily.getText()).isEqualTo("85 / 100");
		assertThat(daily.getThemeNames()).contains(BadgeVariant.WARNING.getVariantName());
		assertThat(grid.getCellText(row, 6)).isEqualTo("Never");
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void filtersById() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.HOURLY, 10)));
		quotaDefinitionRepository.save(new QuotaDefinition(uniqueId(), Map.of(QuotaPeriod.HOURLY, 10)));

		navigate(QuotaView.class);
		test(find(TextField.class).withPlaceholder("Filter by id").single()).setValue(id.toUpperCase());

		var grid = grid();
		assertThat(grid.size()).isEqualTo(1);
		assertThat(grid.getRow(0).id()).isEqualTo(id);
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void createsDefinition() {
		var id = uniqueId();
		navigate(QuotaView.class);

		test(find(Button.class).withText("New quota").single()).click();
		var editor = find(QuotaEditor.class).single();
		test(find(TextField.class, editor).withLabel("Id").single()).setValue(id);
		test(find(IntegerField.class, editor).withLabel("Hourly limit").single()).setValue(10);
		test(find(IntegerField.class, editor).withLabel("Monthly limit").single()).setValue(1000);
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(quotaDefinitionRepository.findById(id)).get()
				.extracting(QuotaDefinition::limits)
				.isEqualTo(Map.of(QuotaPeriod.HOURLY, 10, QuotaPeriod.MONTHLY, 1000));
		assertThat(notifications()).contains("Quota " + id + " saved");
		assertThat(grid().getSelected()).extracting(QuotaRow::id).containsExactly(id);
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void rejectsDuplicateId() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.HOURLY, 10)));
		navigate(QuotaView.class);

		test(find(Button.class).withText("New quota").single()).click();
		var editor = find(QuotaEditor.class).single();
		var idField = find(TextField.class, editor).withLabel("Id").single();
		test(idField).setValue(id);
		test(find(IntegerField.class, editor).withLabel("Hourly limit").single()).setValue(99);
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(idField.isInvalid()).isTrue();
		assertThat(idField.getErrorMessage()).isEqualTo("A quota with this id already exists");
		assertThat(quotaDefinitionRepository.findById(id)).get()
				.extracting(QuotaDefinition::limits).isEqualTo(Map.of(QuotaPeriod.HOURLY, 10));
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void changesLimits() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.HOURLY, 10, QuotaPeriod.DAILY, 100)));
		var editor = openEditor(id);

		test(find(IntegerField.class, editor).withLabel("Hourly limit").single()).setValue(20);
		test(find(IntegerField.class, editor).withLabel("Daily limit").single()).clear();
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(quotaDefinitionRepository.findById(id)).get()
				.extracting(QuotaDefinition::limits).isEqualTo(Map.of(QuotaPeriod.HOURLY, 20));
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void setsAndResetsUsage() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.HOURLY, 10)));
		var editor = openEditor(id);

		var card = usageCard(editor, "Hourly");
		test(find(IntegerField.class, card).single()).setValue(7);
		test(find(Button.class, card).withText("Set").single()).click();

		assertThat(quotaManager.getUsage(id, QuotaPeriod.HOURLY)).isEqualTo(7);
		// Saving usage rebuilds the editor with the new status
		var refreshedCard = usageCard(find(QuotaEditor.class).single(), "Hourly");
		assertThat(refreshedCard.getSubtitle().getElement().getText()).isEqualTo("7 of 10 used, 3 left");

		test(find(Button.class, refreshedCard).withText("Reset").single()).click();

		assertThat(quotaManager.getUsage(id, QuotaPeriod.HOURLY)).isZero();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void deletesDefinitionAfterConfirmation() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, Map.of(QuotaPeriod.HOURLY, 10)));
		var editor = openEditor(id);

		test(find(Button.class, editor).withText("Delete").single()).click();
		var dialog = test(find(ConfirmDialog.class).single());
		assertThat(dialog.getHeader()).isEqualTo("Delete quota " + id + "?");
		dialog.confirm();

		assertThat(quotaDefinitionRepository.existsById(id)).isFalse();
		assertThat(find(QuotaEditor.class).exists()).isFalse();
		var grid = grid();
		assertThat(IntStream.range(0, grid.size()).mapToObj(grid::getRow))
				.extracting(QuotaRow::id).doesNotContain(id);
	}

	private QuotaEditor openEditor(String id) {
		navigate(QuotaView.class);
		var grid = grid();
		grid.select(rowOf(grid, id));
		return find(QuotaEditor.class).single();
	}

	private Card usageCard(QuotaEditor editor, String title) {
		return find(Card.class, editor).withCondition(card -> title.equals(card.getTitleAsText())).single();
	}

	@SuppressWarnings("unchecked")
	private GridTester<Grid<QuotaRow>, QuotaRow> grid() {
		return test((Grid<QuotaRow>) find(Grid.class).single());
	}

	private static int rowOf(GridTester<Grid<QuotaRow>, QuotaRow> grid, String id) {
		return IntStream.range(0, grid.size())
				.filter(row -> grid.getRow(row).id().equals(id))
				.findFirst().orElseThrow(() -> new AssertionError("No row for " + id));
	}

	private List<String> notifications() {
		return find(Notification.class).all().stream().map(notification -> test(notification).getText()).toList();
	}

	private static String uniqueId() {
		return "ui-test-" + UUID.randomUUID();
	}
}
