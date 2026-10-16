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
package biz.asplund.redis.sms;

import biz.asplund.redis.quota.QuotaConfig;
import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.redis.security.UserAccountRepository;
import biz.asplund.redis.security.UserAccountService;
import biz.asplund.redis.security.UserProfile;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Sending SMS charged to the user's quota, with the gateway mocked.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class SmsServiceIT {

	static final String WITH_QUOTA = "sms-with-quota";
	static final String WITHOUT_QUOTA = "sms-without-quota";
	static final String TEXT = "Hello";
	static final String SENDER = "Lekab";

	@Autowired
	private SmsService smsService;

	@Autowired
	private QuotaManager quotaManager;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@MockitoBean
	private SmsSender smsSender;

	@BeforeEach
	void storeUsers() {
		if (!userAccountRepository.existsByUsername(WITH_QUOTA)) {
			userAccountService.createUser(WITH_QUOTA, "password1", true, List.of("USER"),
					new QuotaConfig(10, QuotaPeriod.DAILY), UserProfile.NONE, List.of(SENDER, "+46701234500"));
		}
		if (!userAccountRepository.existsByUsername(WITHOUT_QUOTA)) {
			userAccountService.createUser(WITHOUT_QUOTA, "password1", true, List.of("USER"), null,
					UserProfile.NONE, List.of(SENDER));
		}
		quotaManager.resetQuota(WITH_QUOTA);
	}

	@Test
	void countsParts() {
		assertThat(SmsParts.count("")).isZero();
		assertThat(SmsParts.count("a".repeat(160))).isEqualTo(1);
		assertThat(SmsParts.count("a".repeat(161))).isEqualTo(2);
		assertThat(SmsParts.count("a".repeat(306))).isEqualTo(2);
		assertThat(SmsParts.count("a".repeat(307))).isEqualTo(3);
		// Swedish letters are in the GSM alphabet; the extension characters take two
		assertThat(SmsParts.count("å".repeat(160))).isEqualTo(1);
		assertThat(SmsParts.count("€".repeat(80))).isEqualTo(1);
		assertThat(SmsParts.count("€".repeat(81))).isEqualTo(2);
		// Any other character makes it UCS-2, where an emoji takes two code units
		assertThat(SmsParts.isGsm("ą")).isFalse();
		assertThat(SmsParts.count("ą".repeat(70))).isEqualTo(1);
		assertThat(SmsParts.count("ą".repeat(71))).isEqualTo(2);
		assertThat(SmsParts.count("ą".repeat(134))).isEqualTo(2);
		assertThat(SmsParts.count("ą".repeat(135))).isEqualTo(3);
		assertThat(SmsParts.count("a".repeat(69) + "😀")).isEqualTo(2);
	}

	@Test
	void normalizesMobileNumbers() {
		assertThat(smsService.defaultRegion()).isEqualTo("SE");
		assertThat(smsService.normalizeRecipient("+46 70-123 45 67")).contains("+46701234567");
		// Without a country code, read in the default region
		assertThat(smsService.normalizeRecipient("(070) 123.45.67")).contains("+46701234567");
		assertThat(smsService.normalizeRecipient("0046701234567")).contains("+46701234567");
		assertThat(smsService.normalizeRecipient("+44 7400 123456")).contains("+447400123456");
		// A fixed-line number, one too short, and no number at all
		assertThat(smsService.normalizeRecipient("08-123 456 78")).isEmpty();
		assertThat(smsService.normalizeRecipient("+46 70 123")).isEmpty();
		assertThat(smsService.normalizeRecipient("not a number")).isEmpty();
	}

	@Test
	void checksDefaultRegion() {
		assertThat(new SmsProperties(" no ").defaultRegion()).isEqualTo("NO");
		assertThatIllegalArgumentException().isThrownBy(() -> new SmsProperties("XX"));
	}

	@Test
	void normalizesSenderIds() {
		assertThat(SmsService.normalizeSenderId(" Lekab ")).contains("Lekab");
		assertThat(SmsService.normalizeSenderId("Lekab & Co")).contains("Lekab & Co");
		assertThat(SmsService.normalizeSenderId("Shop-24.se")).contains("Shop-24.se");
		assertThat(SmsService.normalizeSenderId("A".repeat(11))).contains("A".repeat(11));
		// A phone number is normalized as a recipient is
		assertThat(SmsService.normalizeSenderId("+46 70-123 45 67")).contains("+46701234567");
		assertThat(SmsService.normalizeSenderId("A".repeat(12))).isEmpty();
		assertThat(SmsService.normalizeSenderId("12345")).isEmpty();
		assertThat(SmsService.normalizeSenderId("Lekab!")).isEmpty();
		assertThat(SmsService.normalizeSenderId("Läkab")).isEmpty();
		assertThat(SmsService.normalizeSenderId(" ")).isEmpty();
	}

	@Test
	void sendsFromPhoneNumber() {
		var result = smsService.send(WITH_QUOTA, "+46701234500", List.of("+46701234567"), TEXT);

		assertThat(result.outcome()).isEqualTo(SmsSendResult.Outcome.SENT);
		verify(smsSender).send("+46701234500", "+46701234567", TEXT);
	}

	@Test
	void chargesEachPartToEachRecipient() {
		var text = "a".repeat(161);

		var result = smsService.send(WITH_QUOTA, SENDER,
				List.of("+46701234567", "+46701234568", "+46701234569"), text);

		assertThat(result.outcome()).isEqualTo(SmsSendResult.Outcome.SENT);
		assertThat(result.cost()).isEqualTo(6);
		assertThat(result.sent()).hasSize(3);
		assertThat(result.failed()).isEmpty();
		assertThat(result.quota().remaining()).isEqualTo(4);
		assertThat(quotaManager.getUsage(WITH_QUOTA)).isEqualTo(6);
		assertThat(quotaManager.getLastUsed(WITH_QUOTA)).isPresent();
		verify(smsSender).send(SENDER, "+46701234567", text);
		verify(smsSender).send(SENDER, "+46701234568", text);
		verify(smsSender).send(SENDER, "+46701234569", text);
	}

	@Test
	void sendsNothingWhenQuotaHasNoRoom() {
		quotaManager.setUsage(WITH_QUOTA, 8);

		var result = smsService.send(WITH_QUOTA, SENDER,
				List.of("+46701234567", "+46701234568", "+46701234569"), TEXT);

		assertThat(result.outcome()).isEqualTo(SmsSendResult.Outcome.QUOTA_EXCEEDED);
		assertThat(result.cost()).isEqualTo(3);
		assertThat(result.quota().remaining()).isEqualTo(2);
		assertThat(quotaManager.getUsage(WITH_QUOTA)).isEqualTo(8);
		verify(smsSender, never()).send(anyString(), anyString(), any());
	}

	@Test
	void refundsRecipientsTheGatewayRejects() {
		doThrow(new SmsSendException("Unknown number")).when(smsSender).send(SENDER, "+46701234568", TEXT);

		var result = smsService.send(WITH_QUOTA, SENDER,
				List.of("+46701234567", "+46701234568", "+46701234569"), TEXT);

		assertThat(result.outcome()).isEqualTo(SmsSendResult.Outcome.SENT);
		assertThat(result.sent()).containsExactly("+46701234567", "+46701234569");
		assertThat(result.failed()).containsExactly("+46701234568");
		assertThat(quotaManager.getUsage(WITH_QUOTA)).isEqualTo(2);
	}

	@Test
	void sendsNothingFromSenderIdNotApproved() {
		var result = smsService.send(WITH_QUOTA, "Other", List.of("+46701234567"), TEXT);

		assertThat(result.outcome()).isEqualTo(SmsSendResult.Outcome.SENDER_NOT_APPROVED);
		assertThat(quotaManager.getUsage(WITH_QUOTA)).isZero();
		verify(smsSender, never()).send(anyString(), anyString(), any());
	}

	@Test
	void sendsNothingWithoutQuota() {
		var result = smsService.send(WITHOUT_QUOTA, SENDER, List.of("+46701234567"), TEXT);

		assertThat(result.outcome()).isEqualTo(SmsSendResult.Outcome.NO_QUOTA);
		verify(smsSender, never()).send(anyString(), anyString(), any());
	}

	@Test
	void rejectsInvalidInput() {
		assertThatIllegalArgumentException()
				.isThrownBy(() -> smsService.send(WITH_QUOTA, SENDER, List.of(), TEXT));
		assertThatIllegalArgumentException().isThrownBy(() -> smsService.send(WITH_QUOTA, "Far too long",
				List.of("+46701234567"), TEXT));
		assertThatIllegalArgumentException().isThrownBy(() -> smsService.send(WITH_QUOTA, "+46 70 123 45 00",
				List.of("+46701234567"), TEXT));
		assertThatIllegalArgumentException().isThrownBy(() -> smsService.send(WITH_QUOTA, SENDER,
				List.of("+46701234567", "+46701234567"), TEXT));
		assertThatIllegalArgumentException().isThrownBy(() -> smsService.send(WITH_QUOTA, SENDER,
				List.of("+46 70 123 45 67"), TEXT));
		assertThatIllegalArgumentException().isThrownBy(() -> smsService.send(WITH_QUOTA, SENDER,
				List.of("+46812345678"), TEXT));
		assertThatIllegalArgumentException().isThrownBy(() -> smsService.send(WITH_QUOTA, SENDER,
				List.of("+46701234567"), " "));
		assertThatIllegalArgumentException().isThrownBy(() -> smsService.send(WITH_QUOTA, SENDER,
				List.of("+46701234567"), "a".repeat(153 * SmsService.MAX_PARTS + 1)));
		assertThat(quotaManager.getUsage(WITH_QUOTA)).isZero();
		verify(smsSender, never()).send(anyString(), anyString(), any());
	}
}
