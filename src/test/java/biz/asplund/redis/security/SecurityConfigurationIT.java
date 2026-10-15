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

package biz.asplund.redis.security;

import biz.asplund.redis.TestcontainersConfiguration;
import biz.asplund.redis.quota.QuotaInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.startsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestBuilders.formLogin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.authenticated;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.security.user.password=secret")
@AutoConfigureMockMvc
class SecurityConfigurationIT {

	@Autowired
	private MockMvc mockMvc;

	// This test is about the security rules only; QuotaInterceptorIT covers the quotas
	@MockitoBean
	private QuotaInterceptor quotaInterceptor;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@BeforeEach
	void allowApiRequests() throws Exception {
		when(quotaInterceptor.preHandle(any(), any(), any())).thenReturn(true);
	}

	@Test
	void redirectsAnonymousUiRequestsToLogin() throws Exception {
		mockMvc.perform(get("/"))
				.andExpect(status().is3xxRedirection())
				.andExpect(redirectedUrl("/login"));
	}

	@Test
	void logsInWithConfiguredCredentials() throws Exception {
		mockMvc.perform(formLogin("/login").user("admin").password("secret"))
				.andExpect(authenticated().withUsername("admin").withRoles("ADMIN"))
				.andExpect(redirectedUrl("/"));
	}

	@Test
	void rejectsWrongPassword() throws Exception {
		mockMvc.perform(formLogin("/login").user("admin").password("wrong"))
				.andExpect(unauthenticated())
				.andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void rejectsDisabledUser() throws Exception {
		var account = new UserAccount("disabled-" + UUID.randomUUID(), passwordEncoder.encode("secret"));
		account.setEnabled(false);
		userAccountRepository.save(account);

		mockMvc.perform(formLogin("/login").user(account.getUsername()).password("secret"))
				.andExpect(unauthenticated())
				.andExpect(redirectedUrl("/login?error"));
	}

	@Test
	void requiresBasicLoginForApi() throws Exception {
		mockMvc.perform(get("/api/usage"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", startsWith("Basic")));
	}

	@Test
	void acceptsBasicLoginForApiWithoutSession() throws Exception {
		mockMvc.perform(get("/api/usage").with(httpBasic("admin", "secret")))
				.andExpect(authenticated().withUsername("admin"))
				.andExpect(request().sessionAttributeDoesNotExist("SPRING_SECURITY_CONTEXT"));
	}
}
