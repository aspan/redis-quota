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
import biz.asplund.redis.sms.SmsSender;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import com.vaadin.browserless.SpringBrowserlessTest;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.RouteNotFoundError;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithAnonymousUser;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The send SMS view, which charges what it sends to the user's own quota, with the gateway mocked.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SendSmsViewIT extends SpringBrowserlessTest {

	static final String WITH_QUOTA = "send-sms-with-quota";
	static final String WITHOUT_QUOTA = "send-sms-without-quota";

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@Autowired
	private QuotaManager quotaManager;

	@MockitoBean
	private SmsSender smsSender;

	@BeforeEach
	void storeUsers() {
		if (!userAccountRepository.existsByUsername(WITH_QUOTA)) {
			userAccountService.createUser(WITH_QUOTA, "password1", true, List.of("USER"),
					new QuotaConfig(10, QuotaPeriod.DAILY));
		}
		if (!userAccountRepository.existsByUsername(WITHOUT_QUOTA)) {
			userAccountService.createUser(WITHOUT_QUOTA, "password1", true, List.of("USER"), null);
		}
		quotaManager.resetQuota(WITH_QUOTA);
	}

	@Test
	@WithAnonymousUser
	void requiresLogin() {
		navigate("sms", LoginView.class);
	}

	@Test
	@WithMockUser(username = WITH_QUOTA, roles = "OTHER")
	void requiresUserRole() {
		navigate("sms", RouteNotFoundError.class);
	}

	@Test
	@WithMockUser(username = WITH_QUOTA, roles = "ADMIN")
	void opensForAdmin() {
		navigate("sms", SendSmsView.class);

		assertThat(find(TextArea.class).withLabel("Recipients").exists()).isTrue();
	}

	@Test
	@WithMockUser(username = WITH_QUOTA, roles = "USER")
	void sendsAndChargesQuota() {
		navigate("sms", SendSmsView.class);
		assertThat(usageSummary()).isEqualTo("0 of 10 used, 10 left");

		test(sender()).setValue(" Lekab ");
		test(recipients()).setValue("+46 70 123 45 67, 070-123 45 68\n+46701234567");
		test(message()).setValue("Hello");
		assertThat(find(Span.class).withText("2 recipient(s) × 1 part(s) = 2 unit(s) of your quota").exists())
				.isTrue();
		test(find(Button.class).withText("Send").single()).click();

		verify(smsSender).send("Lekab", "+46701234567", "Hello");
		verify(smsSender).send("Lekab", "+46701234568", "Hello");
		assertThat(usageSummary()).isEqualTo("2 of 10 used, 8 left");
		assertThat(message().getValue()).isEmpty();
		// The sender and recipients are kept for the next message
		assertThat(sender().getValue()).isEqualTo(" Lekab ");
	}

	@Test
	@WithMockUser(username = WITH_QUOTA, roles = "USER")
	void showsPartsOfLongMessage() {
		navigate("sms", SendSmsView.class);

		test(message()).setValue("a".repeat(161));
		assertThat(message().getHelperText()).isEqualTo("161 characters, 2 part(s)");
		test(message()).setValue("ą");
		assertThat(message().getHelperText())
				.isEqualTo("1 characters, 1 part(s); special characters make each part shorter");
	}

	@Test
	@WithMockUser(username = WITH_QUOTA, roles = "USER")
	void rejectsInvalidInput() {
		navigate("sms", SendSmsView.class);

		test(sender()).setValue("Far too long");
		test(recipients()).setValue("+46701234567, not a number\n08-123 456 78");
		test(find(Button.class).withText("Send").single()).click();

		assertThat(sender().isInvalid()).isTrue();
		assertThat(recipients().isInvalid()).isTrue();
		assertThat(recipients().getErrorMessage()).isEqualTo("Not mobile phone numbers: not a number, 08-123 456 78");
		assertThat(message().isInvalid()).isTrue();
		verify(smsSender, never()).send(anyString(), anyString(), any());
		assertThat(quotaManager.getUsage(WITH_QUOTA)).isZero();
	}

	@Test
	@WithMockUser(username = WITH_QUOTA, roles = "USER")
	void sendsNothingWhenQuotaHasNoRoom() {
		quotaManager.setUsage(WITH_QUOTA, 9);
		navigate("sms", SendSmsView.class);

		test(sender()).setValue("+46701234500");
		test(recipients()).setValue("+46701234567, +46701234568");
		test(message()).setValue("Hello");
		test(find(Button.class).withText("Send").single()).click();

		verify(smsSender, never()).send(anyString(), anyString(), any());
		assertThat(usageSummary()).isEqualTo("9 of 10 used, 1 left");
		assertThat(message().getValue()).isEqualTo("Hello");
	}

	@Test
	@WithMockUser(username = WITHOUT_QUOTA, roles = "USER")
	void tellsUserWithoutQuota() {
		navigate("sms", SendSmsView.class);

		assertThat(find(TextArea.class).exists()).isFalse();
		assertThat(find(TextField.class).withLabel("Sender").exists()).isFalse();
		assertThat(find(Span.class)
				.withText("You have no quota, so you cannot send SMS. Ask an admin to give you one.").exists())
				.isTrue();
	}

	private TextField sender() {
		return find(TextField.class).withLabel("Sender").single();
	}

	private TextArea recipients() {
		return find(TextArea.class).withLabel("Recipients").single();
	}

	private TextArea message() {
		return find(TextArea.class).withLabel("Message").single();
	}

	private String usageSummary() {
		return find(QuotaUsageCard.class).single().getSubtitle().getElement().getText();
	}
}
