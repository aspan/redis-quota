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

import biz.asplund.redis.security.UserAccountService;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * API requests logged in with HTTP Basic, counted against the user's quota in Redis.
 */
@Import({TestcontainersConfiguration.class, QuotaInterceptorIT.PingController.class})
@SpringBootTest
@AutoConfigureMockMvc
class QuotaInterceptorIT {

	private static final String PASSWORD = "password1";

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private QuotaManager quotaManager;

	// An API endpoint for the tests, which echoes the usage the interceptor stored in the request
	@RestController
	static class PingController {

		@GetMapping("/api/ping")
		String ping(@RequestAttribute(QuotaInterceptor.STATUS_ATTRIBUTE) QuotaStatus status) {
			return "used " + status.used();
		}
	}

	@Test
	void countsRequestsAgainstUserQuota() throws Exception {
		var username = createUser(new QuotaConfig(2, QuotaPeriod.DAILY));

		mockMvc.perform(get("/api/ping").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(content().string("used 1"))
				.andExpect(header().string("X-Quota-Limit", "2"))
				.andExpect(header().string("X-Quota-Used", "1"))
				.andExpect(header().string("X-Quota-Remaining", "1"))
				.andExpect(header().exists("X-Quota-Reset"));
		mockMvc.perform(get("/api/ping").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(content().string("used 2"));

		mockMvc.perform(get("/api/ping").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isTooManyRequests())
				.andExpect(header().string("X-Quota-Remaining", "0"))
				.andExpect(jsonPath("$.error").value("Quota Exceeded"))
				.andExpect(jsonPath("$.limit").value(2))
				.andExpect(jsonPath("$.used").value(2))
				.andExpect(jsonPath("$.remaining").value(0))
				.andExpect(jsonPath("$.resetAt").exists());

		assertUsage(username, 2);
	}

	@Test
	void refusesUserWithoutQuota() throws Exception {
		var username = createUser(null);

		mockMvc.perform(get("/api/ping").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("No Quota"))
				.andExpect(jsonPath("$.message").value("No quota is set for user " + username));
	}

	@Test
	void doesNotCountRequestsThatFailToLogIn() throws Exception {
		var username = createUser(new QuotaConfig(2, QuotaPeriod.DAILY));

		mockMvc.perform(get("/api/ping").with(httpBasic(username, "wrong-password")))
				.andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/ping"))
				.andExpect(status().isUnauthorized());

		assertUsage(username, 0);
	}

	@Test
	void refusesUserWithoutUserRole() throws Exception {
		var username = userAccountService.createUser("api-" + UUID.randomUUID(), PASSWORD, true, List.of(),
				new QuotaConfig(2, QuotaPeriod.DAILY)).getUsername();

		mockMvc.perform(get("/api/ping").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isForbidden())
				.andExpect(jsonPath("$.error").value("Forbidden"))
				.andExpect(jsonPath("$.message").value("The API requires the USER role"));

		assertUsage(username, 0);
	}

	@Test
	void letsAdminUseApiThroughRoleHierarchy() throws Exception {
		var username = userAccountService.createUser("api-" + UUID.randomUUID(), PASSWORD, true, List.of("ADMIN"),
				new QuotaConfig(2, QuotaPeriod.DAILY)).getUsername();

		mockMvc.perform(get("/api/ping").with(httpBasic(username, PASSWORD)))
				.andExpect(status().isOk())
				.andExpect(content().string("used 1"));
	}

	// API clients have the USER role
	private String createUser(QuotaConfig quota) {
		return userAccountService.createUser("api-" + UUID.randomUUID(), PASSWORD, true, List.of("USER"), quota)
				.getUsername();
	}

	private void assertUsage(String username, int usage) {
		assertThat(quotaManager.getUsage(username)).isEqualTo(usage);
	}
}
