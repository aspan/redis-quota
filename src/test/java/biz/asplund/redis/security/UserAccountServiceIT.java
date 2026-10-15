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
import biz.asplund.redis.quota.QuotaConfig;
import biz.asplund.redis.quota.QuotaDefinition;
import biz.asplund.redis.quota.QuotaDefinitionRepository;
import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.redis.security.AccountRuleException.Rule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Managing users, roles and quotas in PostgreSQL, the copies of the quotas in Redis, and the rules that keep
 * an admin able to log in.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
class UserAccountServiceIT {

	@Autowired
	private UserAccountService userAccountService;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@Autowired
	private DatabaseUserDetailsService userDetailsService;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private QuotaDefinitionRepository quotaDefinitionRepository;

	@Autowired
	private QuotaManager quotaManager;

	@Test
	void createsUserWithEncodedPasswordAndRoles() {
		var username = uniqueName();

		var account = userAccountService.createUser(" " + username + " ", "password1", true, List.of("ADMIN"), null);

		var user = userDetailsService.loadUserByUsername(username);
		assertThat(account.getUsername()).isEqualTo(username);
		assertThat(passwordEncoder.matches("password1", user.getPassword())).isTrue();
		assertThat(user.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_ADMIN");
	}

	@Test
	void rejectsInvalidNewUser() {
		var username = uniqueName();
		userAccountService.createUser(username, "password1", true, List.of(), null);

		assertRule(Rule.USERNAME_REQUIRED,
				() -> userAccountService.createUser(" ", "password1", true, List.of(), null));
		assertRule(Rule.USERNAME_TAKEN,
				() -> userAccountService.createUser(username, "password1", true, List.of(), null));
		assertRule(Rule.PASSWORD_TOO_SHORT,
				() -> userAccountService.createUser(uniqueName(), "short", true, List.of(), null));
		assertRule(Rule.NOT_FOUND,
				() -> userAccountService.createUser(uniqueName(), "password1", true, List.of("NO_SUCH_ROLE"), null));
		assertRule(Rule.QUOTA_LIMIT_INVALID, () -> userAccountService.createUser(uniqueName(), "password1", true,
				List.of(), new QuotaConfig(-1, QuotaPeriod.DAILY)));
	}

	@Test
	void updatesRolesAndEnabledAndKeepsPasswordWhenEmpty() {
		var role = userAccountService.createRole(uniqueRoleName());
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of("ADMIN"), null);

		userAccountService.updateUser(account.getId(), "", false, Set.of(role.getName()), null, "someone-else");

		var user = userDetailsService.loadUserByUsername(account.getUsername());
		assertThat(user.isEnabled()).isFalse();
		assertThat(user.getAuthorities()).extracting(Object::toString).containsExactly("ROLE_" + role.getName());
		assertThat(passwordEncoder.matches("password1", user.getPassword())).isTrue();
	}

	@Test
	void changesPassword() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(), null);

		userAccountService.updateUser(account.getId(), "password2", true, List.of(), null, "someone-else");

		var user = userDetailsService.loadUserByUsername(account.getUsername());
		assertThat(passwordEncoder.matches("password2", user.getPassword())).isTrue();
	}

	@Test
	void keepsOwnAccountAnEnabledAdmin() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of("ADMIN"), null);
		var self = account.getUsername();

		assertRule(Rule.OWN_ACCOUNT_DISABLE,
				() -> userAccountService.updateUser(account.getId(), null, false, List.of("ADMIN"), null, self));
		assertRule(Rule.OWN_ADMIN_ROLE,
				() -> userAccountService.updateUser(account.getId(), null, true, List.of(), null, self));
		assertRule(Rule.OWN_ACCOUNT_DELETE, () -> userAccountService.deleteUser(account.getId(), self));

		userAccountService.deleteUser(account.getId(), "someone-else");

		assertThat(userAccountRepository.existsByUsername(self)).isFalse();
	}

	@Test
	void copiesQuotaToRedisUnderUsername() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				new QuotaConfig(500, QuotaPeriod.DAILY));
		var username = account.getUsername();

		assertThat(quotaDefinitionRepository.findById(username))
				.contains(new QuotaDefinition(username, QuotaPeriod.DAILY, 500));
		assertThat(userAccountService.findQuotaUsernames()).contains(username);
		assertThat(quotaManager.checkAndIncrement(username, 2).used()).isEqualTo(2);

		userAccountService.updateUser(account.getId(), null, true, List.of(), new QuotaConfig(10, QuotaPeriod.WEEKLY),
				"someone-else");

		assertThat(quotaDefinitionRepository.findById(username))
				.contains(new QuotaDefinition(username, QuotaPeriod.WEEKLY, 10));
		assertThat(userAccountRepository.findByUsername(username).orElseThrow().getQuota())
				.contains(new QuotaConfig(10, QuotaPeriod.WEEKLY));

		userAccountService.updateUser(account.getId(), null, true, List.of(), null, "someone-else");

		assertThat(quotaDefinitionRepository.existsById(username)).isFalse();
		assertThat(userAccountService.findQuotaUsernames()).doesNotContain(username);
	}

	@Test
	void deletesQuotaWithUser() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				new QuotaConfig(500, QuotaPeriod.DAILY));

		userAccountService.deleteUser(account.getId(), "someone-else");

		assertThat(quotaDefinitionRepository.existsById(account.getUsername())).isFalse();
	}

	@Test
	void leavesDefinitionOfUserWithoutQuota() {
		var username = uniqueName();
		quotaDefinitionRepository.save(new QuotaDefinition(username, QuotaPeriod.HOURLY, 7));
		var account = userAccountService.createUser(username, "password1", true, List.of(), null);

		userAccountService.updateUser(account.getId(), null, true, List.of(), null, "someone-else");
		userAccountService.deleteUser(account.getId(), "someone-else");

		assertThat(quotaDefinitionRepository.findById(username))
				.contains(new QuotaDefinition(username, QuotaPeriod.HOURLY, 7));
	}

	@Test
	void copiesQuotasToRedisAgain() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				new QuotaConfig(500, QuotaPeriod.MONTHLY));
		quotaDefinitionRepository.deleteById(account.getUsername());

		assertThat(userAccountService.copyQuotasToRedis()).isPositive();

		assertThat(quotaDefinitionRepository.findById(account.getUsername()))
				.contains(new QuotaDefinition(account.getUsername(), QuotaPeriod.MONTHLY, 500));
	}

	@Test
	void createsRoleInUpperCase() {
		var name = uniqueRoleName();

		var role = userAccountService.createRole(" " + name.toLowerCase(Locale.ROOT) + " ");

		assertThat(role.getName()).isEqualTo(name);
		assertRule(Rule.ROLE_EXISTS, () -> userAccountService.createRole(name));
		assertRule(Rule.ROLE_NAME_INVALID, () -> userAccountService.createRole("ROLE_" + name));
		assertRule(Rule.ROLE_NAME_INVALID, () -> userAccountService.createRole("1ST"));
		assertRule(Rule.ROLE_NAME_INVALID, () -> userAccountService.createRole("WITH SPACE"));
	}

	@Test
	void deletingRoleRemovesItFromUsers() {
		var role = userAccountService.createRole(uniqueRoleName());
		var account = userAccountService.createUser(uniqueName(), "password1", true,
				List.of("ADMIN", role.getName()), null);
		assertThat(userAccountService.countUsersByRole()).containsEntry(role.getId(), 1L);

		userAccountService.deleteRole(role.getId());

		assertThat(userAccountService.findAllRoles()).extracting(Role::getName).doesNotContain(role.getName());
		assertThat(userDetailsService.loadUserByUsername(account.getUsername()).getAuthorities())
				.extracting(Object::toString).containsExactly("ROLE_ADMIN");
	}

	@Test
	void keepsAdminRole() {
		var admin = userAccountService.findAllRoles().stream()
				.filter(role -> role.getName().equals("ADMIN")).findFirst().orElseThrow();

		assertRule(Rule.ADMIN_ROLE_DELETE, () -> userAccountService.deleteRole(admin.getId()));
	}

	private static void assertRule(Rule rule, Runnable change) {
		assertThatExceptionOfType(AccountRuleException.class).isThrownBy(change::run)
				.satisfies(e -> assertThat(e.rule()).isEqualTo(rule));
	}

	private static String uniqueName() {
		return "user-" + UUID.randomUUID();
	}

	private static String uniqueRoleName() {
		return "R" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
	}
}
