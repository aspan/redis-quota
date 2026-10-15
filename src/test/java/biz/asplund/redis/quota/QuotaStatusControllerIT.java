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

package biz.asplund.redis.quota;

import biz.asplund.redis.TestcontainersConfiguration;
import biz.asplund.redis.security.UserAccountService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GET /api/quota, which shows API clients their own quota without using it up.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class QuotaStatusControllerIT {

	private static final String PASSWORD = "password1";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private QuotaManager quotaManager;

	@Test
	void showsOwnQuotaWithoutUsingIt() throws Exception {
		var username = createUser(new QuotaConfig(5, QuotaPeriod.DAILY));
		quotaManager.checkAndIncrement(username, 2);

		mockMvc.perform(get("/api/quota").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.id").value(username))
				.andExpect(jsonPath("$.period").value("DAILY"))
				.andExpect(jsonPath("$.limit").value(5))
				.andExpect(jsonPath("$.used").value(2))
				.andExpect(jsonPath("$.remaining").value(3))
				.andExpect(jsonPath("$.resetAt").isString())
				.andExpect(jsonPath("$.lastUsed").isString())
				.andExpect(header().doesNotExist("X-Quota-Used"));

		assertThat(quotaManager.getUsage(username)).isEqualTo(2);
	}

	@Test
	void showsUsedUpQuota() throws Exception {
		var username = createUser(new QuotaConfig(1, QuotaPeriod.HOURLY));
		quotaManager.checkAndIncrement(username, 1);

		mockMvc.perform(get("/api/quota").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.remaining").value(0));
	}

	@Test
	void showsNoLastUseBeforeFirstRequest() throws Exception {
		var username = createUser(new QuotaConfig(5, QuotaPeriod.MONTHLY));

		mockMvc.perform(get("/api/quota").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.used").value(0))
				.andExpect(jsonPath("$.lastUsed").isEmpty());
	}

	@Test
	void refusesUserWithoutQuota() throws Exception {
		var username = createUser(null);

		mockMvc.perform(get("/api/quota").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("No Quota"));
	}

	@Test
	void requiresLogin() throws Exception {
		mockMvc.perform(get("/api/quota"))
				.andExpect(status().isUnauthorized());
	}

	private String createUser(QuotaConfig quota) {
		return userAccountService.createUser("api-" + UUID.randomUUID(), PASSWORD, true, List.of(), quota)
				.getUsername();
	}
}
