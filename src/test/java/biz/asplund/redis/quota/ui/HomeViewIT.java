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
import biz.asplund.redis.security.UserAccountRepository;
import biz.asplund.redis.security.UserAccountService;
import biz.asplund.redis.security.UserProfile;
import biz.asplund.redis.security.UserSettings;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The home view, where every logged-in user lands and sees their own quota.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class HomeViewIT extends SpringBrowserlessTest {

	static final String WITH_QUOTA = "home-with-quota";
	static final String WITHOUT_QUOTA = "home-without-quota";

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@Autowired
	private QuotaManager quotaManager;

	@BeforeEach
	void storeUsers() {
		if (!userAccountRepository.existsByUsername(WITH_QUOTA)) {
			userAccountService.createUser(WITH_QUOTA, "password1", true, List.of("USER"),
					new QuotaConfig(10, QuotaPeriod.DAILY));
		}
		if (!userAccountRepository.existsByUsername(WITHOUT_QUOTA)) {
			userAccountService.createUser(WITHOUT_QUOTA, "password1", true, List.of("USER"), null);
		}
	}

	@Test
	@WithAnonymousUser
	void requiresLogin() {
		navigate("", LoginView.class);
	}

	@Test
	@WithMockUser(username = WITH_QUOTA, roles = "USER")
	void showsOwnQuotaReadOnly() {
		quotaManager.setUsage(WITH_QUOTA, 4);

		navigate("", HomeView.class);

		assertThat(find(Span.class).withText("Welcome, " + WITH_QUOTA).exists()).isTrue();
		var card = find(QuotaUsageCard.class).single();
		assertThat(card.getSubtitle().getElement().getText()).isEqualTo("4 of 10 used, 6 left");
		// Only admins change usage
		assertThat(find(Button.class, card).exists()).isFalse();
		assertThat(find(UsageHistorySection.class).exists()).isTrue();
	}

	@Test
	@WithMockUser(username = WITHOUT_QUOTA, roles = "USER")
	void greetsUserByName() {
		userAccountService.changeOwnProfile(WITHOUT_QUOTA, new UserProfile("Anna Andersson", null, UserSettings.NONE));
		try {
			navigate("", HomeView.class);

			assertThat(find(Span.class).withText("Welcome, Anna Andersson").exists()).isTrue();
		} finally {
			userAccountService.changeOwnProfile(WITHOUT_QUOTA, UserProfile.NONE);
		}
	}

	@Test
	@WithMockUser(username = WITHOUT_QUOTA, roles = "USER")
	void tellsUserWithoutQuota() {
		navigate("", HomeView.class);

		assertThat(find(QuotaUsageCard.class).exists()).isFalse();
		assertThat(find(Span.class)
				.withText("You have no quota, so you cannot use the API. Ask an admin to give you one.").exists())
				.isTrue();
	}
}
