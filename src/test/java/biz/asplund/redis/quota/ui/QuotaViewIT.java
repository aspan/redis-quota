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
import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.redis.security.QuotaUsageRecord;
import biz.asplund.redis.security.QuotaUsageRecordRepository;
import biz.asplund.redis.security.UserAccountRepository;
import biz.asplund.redis.security.UserAccountService;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.badge.Badge;
import com.vaadin.flow.component.badge.BadgeVariant;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.card.Card;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.GridTester;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.Scroller;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.progressbar.ProgressBarVariant;
import com.vaadin.flow.component.select.Select;
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
import org.vaadin.addons.chartjs.config.BarChartConfig;
import org.vaadin.addons.chartjs.data.BarDataset;

import java.time.Instant;
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
	private UserAccountService userAccountService;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@Autowired
	private QuotaUsageRecordRepository quotaUsageRecordRepository;

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
		navigate("quotas", RouteNotFoundError.class);
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void showsQuotasViewInLayoutWithMenu() {
		navigate(QuotaView.class);

		assertThat(find(H1.class).single().getText()).isEqualTo("Quotas");
		assertThat(find(SideNav.class).single().getItems())
				.extracting(SideNavItem::getLabel, SideNavItem::getPath)
				.containsExactly(tuple("Home", ""), tuple("Send SMS", "sms"), tuple("Quotas", "quotas"),
						tuple("Users", "users"), tuple("My profile", "profile"));
		// Log out is at the right end of the navbar, after the language and appearance buttons
		var logout = find(Button.class).withAriaLabel("Log out").single();
		var navbarEnd = logout.getParent().orElseThrow();
		assertThat(navbarEnd.getElement().getAttribute("slot")).isEqualTo("navbar");
		assertThat(navbarEnd.getChildren()).last().isSameAs(logout);
		// With Vaadin's extra-small gap between the buttons
		assertThat(((HorizontalLayout) navbarEnd).getSpacing()).isEqualTo("var(--vaadin-gap-xs)");
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void listsDefinitionsWithUsage() {
		var full = uniqueId();
		var nearlyFull = uniqueId();
		createQuota(full, QuotaPeriod.HOURLY, 10);
		createQuota(nearlyFull, QuotaPeriod.DAILY, 100);
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
		createQuota(id, QuotaPeriod.HOURLY, 10);
		createQuota(uniqueId(), QuotaPeriod.HOURLY, 10);

		navigate(QuotaView.class);
		test(find(TextField.class).withPlaceholder("Filter by id").single()).setValue(id.toUpperCase());

		var grid = grid();
		assertThat(grid.size()).isEqualTo(1);
		assertThat(grid.getRow(0).id()).isEqualTo(id);
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void hasNoNewQuotaButton() {
		navigate(QuotaView.class);

		// Quotas are created with users in the users view
		assertThat(find(Button.class).withText("New quota").exists()).isFalse();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void showsPeriodAndLimitReadOnly() {
		var id = uniqueId();
		createQuota(id, QuotaPeriod.HOURLY, 10);
		var editor = openEditor(id);

		var period = find(Select.class, editor).withLabel("Period").single();
		assertThat(period.getValue()).isEqualTo(QuotaPeriod.HOURLY);
		assertThat(period.isReadOnly()).isTrue();
		var limit = limitField(editor);
		assertThat(limit.getValue()).isEqualTo(10);
		assertThat(limit.isReadOnly()).isTrue();
		assertThat(limit.getHelperText()).isEqualTo("This is a user's quota; change it in the Users view");
		// Quotas are set and removed on users, so only usage changes here
		assertThat(find(Button.class, editor).withText("Save").exists()).isFalse();
		assertThat(find(Button.class, editor).withText("Delete").exists()).isFalse();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void showsUsageHistoryChart() {
		var id = uniqueId();
		createQuota(id, QuotaPeriod.DAILY, 10);
		var record = new QuotaUsageRecord(userAccountRepository.findByUsername(id).orElseThrow(), QuotaPeriod.DAILY,
				Instant.parse("2026-10-12T22:00:00Z"));
		record.update(7, 10);
		quotaUsageRecordRepository.save(record);

		var chart = find(UsageHistoryChart.class, openEditor(id)).single();

		assertThat(((BarChartConfig) chart.getConfig()).data().getLabels()).hasSize(1);
		assertThat(((BarDataset) ((BarChartConfig) chart.getConfig()).data().getDatasetAtIndex(0)).getData())
				.containsExactly(7.0);
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void showsLastUseInUsageCard() {
		var id = uniqueId();
		createQuota(id, QuotaPeriod.HOURLY, 5);

		var card = usageCard(openEditor(id), "Hourly");

		assertThat(find(Div.class, card).withText("Last used Never").exists()).isTrue();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void tellsWhenNoHistoryIsRecorded() {
		var id = uniqueId();
		createQuota(id, QuotaPeriod.HOURLY, 5);

		var editor = openEditor(id);

		assertThat(find(UsageHistoryChart.class, editor).exists()).isFalse();
		assertThat(find(Span.class, editor).withText("No finished periods recorded yet").exists()).isTrue();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void setsAndResetsUsage() {
		var id = uniqueId();
		createQuota(id, QuotaPeriod.HOURLY, 10);
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
		createQuota(id, QuotaPeriod.HOURLY, 10);
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
		createQuota(id, QuotaPeriod.HOURLY, 10);
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
		createQuota(id, QuotaPeriod.HOURLY, 5);
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
		createQuota(id, QuotaPeriod.HOURLY, 5);
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
		createQuota(exactlyTwenty, QuotaPeriod.DAILY, 100);
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
		createQuota(id, QuotaPeriod.HOURLY, 10);
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
		var id = uniqueId();
		createQuota(id, QuotaPeriod.HOURLY, 5);

		var form = find(FormLayout.class, openEditor(id)).single();

		assertThat(form.getWidth()).isEqualTo("100%");
		assertThat(form.getMaxColumns()).isEqualTo(1);
		assertThat(form.isExpandColumns()).isTrue();
		assertThat(form.isExpandFields()).isTrue();
	}

	@Test
	@WithMockUser(username = "admin", roles = "ADMIN")
	void closesEditorOfExistingQuota() {
		var id = uniqueId();
		createQuota(id, QuotaPeriod.HOURLY, 5);
		var editor = openEditor(id);

		test(find(Button.class, editor).withAriaLabel("Close").single()).click();

		assertThat(find(QuotaEditor.class).exists()).isFalse();
		assertThat(grid().getSelected()).isEmpty();
	}

	// Quotas belong to users, so each is created as the quota of a new user with the id as username
	private void createQuota(String id, QuotaPeriod period, int limit) {
		userAccountService.createUser(id, "password1", true, List.of(), new QuotaConfig(limit, period));
	}

	private QuotaEditor openEditor(String id) {
		navigate(QuotaView.class);
		var grid = grid();
		grid.select(rowOf(grid, id));
		return find(QuotaEditor.class).single();
	}

	private IntegerField limitField(QuotaEditor editor) {
		return find(IntegerField.class, editor).withLabel("Limit").single();
	}

	private ProgressBar progressBarWithUsage(int used) {
		var id = uniqueId();
		createQuota(id, QuotaPeriod.DAILY, 20);
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
