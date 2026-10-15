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
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridTester;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.progressbar.ProgressBarVariant;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.select.SelectTester;
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
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

// Browserless tests of the admin UI against a real Redis; each test uses its own quota ids
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class QuotaViewIT extends SpringBrowserlessTest {

	private static final String PROGRESS_COLOR = "--vaadin-progress-bar-value-background";

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
				.containsExactly(tuple("Quotas", ""), tuple("Users", "users"));
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void listsDefinitionsWithUsage() {
		var full = uniqueId();
		var nearlyFull = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(full, QuotaPeriod.HOURLY, 10));
		quotaDefinitionRepository.save(new QuotaDefinition(nearlyFull, QuotaPeriod.DAILY, 100));
		quotaManager.setUsage(full, 10);
		quotaManager.setUsage(nearlyFull, 85);

		navigate(QuotaView.class);
		var grid = grid();

		var fullRow = rowOf(grid, full);
		assertThat(grid.getCellText(fullRow, 1)).isEqualTo("Hourly");
		var fullUsage = (Badge) grid.getCellComponent(fullRow, 2);
		assertThat(fullUsage.getText()).isEqualTo("10 / 10");
		assertThat(fullUsage.getThemeNames()).contains(BadgeVariant.ERROR.getVariantName());
		assertThat(grid.getCellText(fullRow, 3)).isNotBlank();
		assertThat(grid.getCellText(fullRow, 4)).isEqualTo("Never");

		var nearlyFullRow = rowOf(grid, nearlyFull);
		assertThat(grid.getCellText(nearlyFullRow, 1)).isEqualTo("Daily");
		var nearlyFullUsage = (Badge) grid.getCellComponent(nearlyFullRow, 2);
		assertThat(nearlyFullUsage.getText()).isEqualTo("85 / 100");
		assertThat(nearlyFullUsage.getThemeNames()).contains(BadgeVariant.WARNING.getVariantName());
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void filtersById() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 10));
		quotaDefinitionRepository.save(new QuotaDefinition(uniqueId(), QuotaPeriod.HOURLY, 10));

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
		periodField(editor).selectItem("Monthly");
		test(limitField(editor)).setValue(1000);
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(quotaDefinitionRepository.findById(id)).contains(new QuotaDefinition(id, QuotaPeriod.MONTHLY, 1000));
		assertThat(notifications()).contains("Quota " + id + " saved");
		assertThat(grid().getSelected()).extracting(QuotaRow::id).containsExactly(id);
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void requiresPeriodAndLimit() {
		var id = uniqueId();
		navigate(QuotaView.class);

		test(find(Button.class).withText("New quota").single()).click();
		var editor = find(QuotaEditor.class).single();
		test(find(TextField.class, editor).withLabel("Id").single()).setValue(id);
		test(find(Button.class, editor).withText("Save").single()).click();

		var period = find(Select.class, editor).withLabel("Period").single();
		assertThat(period.isInvalid()).isTrue();
		assertThat(period.getErrorMessage()).isEqualTo("Choose a period");
		assertThat(limitField(editor).isInvalid()).isTrue();
		assertThat(limitField(editor).getErrorMessage()).isEqualTo("Enter a limit of 0 or more");
		assertThat(quotaDefinitionRepository.existsById(id)).isFalse();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void rejectsDuplicateId() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 10));
		navigate(QuotaView.class);

		test(find(Button.class).withText("New quota").single()).click();
		var editor = find(QuotaEditor.class).single();
		var idField = find(TextField.class, editor).withLabel("Id").single();
		test(idField).setValue(id);
		periodField(editor).selectItem("Daily");
		test(limitField(editor)).setValue(99);
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(idField.isInvalid()).isTrue();
		assertThat(idField.getErrorMessage()).isEqualTo("A quota with this id already exists");
		assertThat(quotaDefinitionRepository.findById(id)).contains(new QuotaDefinition(id, QuotaPeriod.HOURLY, 10));
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void changesPeriodAndLimit() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 10));
		var editor = openEditor(id);

		var period = periodField(editor);
		assertThat(period.getSelected()).isEqualTo(QuotaPeriod.HOURLY);
		assertThat(find(Select.class, editor).withLabel("Period").single().getHelperText())
				.isEqualTo("Changing the period starts counting usage from zero");
		period.selectItem("Daily");
		test(limitField(editor)).setValue(20);
		test(find(Button.class, editor).withText("Save").single()).click();

		assertThat(quotaDefinitionRepository.findById(id)).contains(new QuotaDefinition(id, QuotaPeriod.DAILY, 20));
		assertThat(usageCard(find(QuotaEditor.class).single(), "Daily")).isNotNull();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void setsAndResetsUsage() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 10));
		var editor = openEditor(id);

		var card = usageCard(editor, "Hourly");
		test(find(IntegerField.class, card).withAriaLabel("New usage, Hourly").single()).setValue(7);
		test(find(Button.class, card).withText("Set").single()).click();

		assertThat(quotaManager.getUsage(id)).isEqualTo(7);
		// Saving usage rebuilds the editor with the new status
		var refreshedCard = usageCard(find(QuotaEditor.class).single(), "Hourly");
		assertThat(refreshedCard.getSubtitle().getElement().getText()).isEqualTo("7 of 10 used, 3 left");

		test(find(Button.class, refreshedCard).withText("Reset").single()).click();

		assertThat(quotaManager.getUsage(id)).isZero();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void adjustsUsageByASignedAmount() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 10));
		quotaManager.setUsage(id, 4);
		var editor = openEditor(id);

		adjustFromCard(usageCard(editor, "Hourly"), 3);

		assertThat(quotaManager.getUsage(id)).isEqualTo(7);
		var card = usageCard(find(QuotaEditor.class).single(), "Hourly");
		assertThat(card.getSubtitle().getElement().getText()).isEqualTo("7 of 10 used, 3 left");

		// A refund never takes usage below zero
		adjustFromCard(card, -20);

		assertThat(quotaManager.getUsage(id)).isZero();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void requiresNonZeroAmountToAdjust() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 10));
		var card = usageCard(openEditor(id), "Hourly");
		var delta = find(IntegerField.class, card).withAriaLabel("Change in usage, Hourly").single();

		test(delta).setValue(0);
		test(find(Button.class, card).withText("Adjust").single()).click();

		assertThat(delta.isInvalid()).isTrue();
		assertThat(delta.getErrorMessage()).isEqualTo("Enter a positive or negative amount");
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void checksAndIncrementsLikeAClient() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 5));
		var editor = openEditor(id);

		requestFromCard(usageCard(editor, "Hourly"), 4);

		assertThat(quotaManager.getUsage(id)).isEqualTo(4);
		assertThat(quotaManager.getLastUsed(id)).isPresent();
		assertThat(notifications()).contains("Allowed: 4 of 5 used, 1 left");
		// The editor is rebuilt with the new status
		var card = usageCard(find(QuotaEditor.class).single(), "Hourly");
		assertThat(card.getSubtitle().getElement().getText()).isEqualTo("4 of 5 used, 1 left");

		requestFromCard(card, 2);

		assertThat(quotaManager.getUsage(id)).isEqualTo(4);
		assertThat(notifications()).contains("Rejected: 2 more would exceed the limit; 4 of 5 used, 1 left");
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void requiresPositiveAmountToCheckAndIncrement() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 5));
		var card = usageCard(openEditor(id), "Hourly");
		var amount = find(IntegerField.class, card).withAriaLabel("Amount to request").single();

		test(amount).setValue(0);
		test(find(Button.class, card).withText("Check and increment").single()).click();

		assertThat(amount.isInvalid()).isTrue();
		assertThat(amount.getErrorMessage()).isEqualTo("Enter 1 or more");
		assertThat(quotaManager.getUsage(id)).isZero();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void colorsProgressBarBySuccessWarningAndError() {
		assertThat(progressBarWithUsage(16)).as("4 of 20 left")
				.satisfies(bar -> assertThat(bar.getStyle().get(PROGRESS_COLOR)).isEqualTo("var(--aura-green)"))
				.satisfies(bar -> assertThat(bar.getThemeNames()).containsExactly(ProgressBarVariant.SUCCESS.getVariantName()));
		assertThat(progressBarWithUsage(17)).as("3 of 20 left")
				.satisfies(bar -> assertThat(bar.getStyle().get(PROGRESS_COLOR)).isEqualTo("var(--aura-orange)"))
				.satisfies(bar -> assertThat(bar.getThemeNames()).isEmpty());
		assertThat(progressBarWithUsage(20)).as("none left")
				.satisfies(bar -> assertThat(bar.getStyle().get(PROGRESS_COLOR)).isEqualTo("var(--aura-red)"))
				.satisfies(bar -> assertThat(bar.getThemeNames()).containsExactly(ProgressBarVariant.ERROR.getVariantName()));
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void badgeWarnsOnlyWhenLessThanTwentyPercentIsLeft() {
		var exactlyTwenty = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(exactlyTwenty, QuotaPeriod.DAILY, 100));
		quotaManager.setUsage(exactlyTwenty, 80);

		navigate(QuotaView.class);
		var grid = grid();
		var badge = (Badge) grid.getCellComponent(rowOf(grid, exactlyTwenty), 2);

		assertThat(badge.getThemeNames()).contains(BadgeVariant.SUCCESS.getVariantName());
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void stretchesUsageActionsAcrossCard() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 10));
		var card = usageCard(openEditor(id), "Hourly");

		assertThat(find(IntegerField.class, card).all()).hasSize(3)
				.allSatisfy(field -> assertThat(field.getWidth()).isEqualTo("100%"));
		assertThat(find(Button.class, card).withText("Adjust").single().getWidth()).isEqualTo("100%");
		assertThat(find(Button.class, card).withText("Check and increment").single().getWidth()).isEqualTo("100%");
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void widensDrawerAndStretchesMenu() {
		navigate(QuotaView.class);

		var layout = find(MainLayout.class).single();
		assertThat(layout.getStyle().get("--vaadin-app-layout-drawer-width")).isEqualTo("20rem");
		var nav = find(SideNav.class).single();
		assertThat(nav.getWidth()).isEqualTo("100%");
		assertThat(((Scroller) nav.getParent().orElseThrow()).getWidth()).isEqualTo("100%");
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void stretchesFormFieldsAcrossEditor() {
		navigate(QuotaView.class);
		test(find(Button.class).withText("New quota").single()).click();

		var form = find(FormLayout.class, find(QuotaEditor.class).single()).single();

		assertThat(form.getWidth()).isEqualTo("100%");
		assertThat(form.getMaxColumns()).isEqualTo(1);
		assertThat(form.isExpandColumns()).isTrue();
		assertThat(form.isExpandFields()).isTrue();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void closesEditorOfExistingQuota() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 5));
		var editor = openEditor(id);

		test(find(Button.class, editor).withAriaLabel("Close").single()).click();

		assertThat(find(QuotaEditor.class).exists()).isFalse();
		assertThat(grid().getSelected()).isEmpty();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void closesEditorOfNewQuota() {
		navigate(QuotaView.class);
		test(find(Button.class).withText("New quota").single()).click();

		test(find(Button.class, find(QuotaEditor.class).single()).withAriaLabel("Close").single()).click();

		assertThat(find(QuotaEditor.class).exists()).isFalse();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void deletesDefinitionAfterConfirmation() {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.HOURLY, 10));
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

	@SuppressWarnings("unchecked")
	private SelectTester<Select<QuotaPeriod>, QuotaPeriod> periodField(QuotaEditor editor) {
		return test((Select<QuotaPeriod>) find(Select.class, editor).withLabel("Period").single());
	}

	private IntegerField limitField(QuotaEditor editor) {
		return find(IntegerField.class, editor).withLabel("Limit").single();
	}

	private ProgressBar progressBarWithUsage(int used) {
		var id = uniqueId();
		quotaDefinitionRepository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 20));
		quotaManager.setUsage(id, used);
		return find(ProgressBar.class, usageCard(openEditor(id), "Daily")).single();
	}

	private void adjustFromCard(Card card, int delta) {
		test(find(IntegerField.class, card).withAriaLabel("Change in usage, Hourly").single()).setValue(delta);
		test(find(Button.class, card).withText("Adjust").single()).click();
	}

	private void requestFromCard(Card card, int amount) {
		test(find(IntegerField.class, card).withAriaLabel("Amount to request").single()).setValue(amount);
		test(find(Button.class, card).withText("Check and increment").single()).click();
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
