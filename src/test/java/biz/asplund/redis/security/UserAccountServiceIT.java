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

import biz.asplund.redis.quota.QuotaConfig;
import biz.asplund.redis.quota.QuotaDefinition;
import biz.asplund.redis.quota.QuotaDefinitionRepository;
import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.redis.security.AccountRuleException.Rule;
import biz.asplund.testcontainers.TestcontainersConfiguration;
import jakarta.validation.ConstraintViolationException;
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
import static org.assertj.core.api.Assertions.catchThrowable;

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
	private RoleRepository roleRepository;

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

		quotaManager.checkAndIncrement(username, 3);

		userAccountService.updateUser(account.getId(), null, true, List.of(), null, "someone-else");

		assertThat(quotaDefinitionRepository.existsById(username)).isFalse();
		assertThat(userAccountService.findQuotaUsernames()).doesNotContain(username);
		// The usage goes with the quota, so a quota given again starts from zero
		assertThat(quotaManager.getLastUsed(username)).isEmpty();
		userAccountService.updateUser(account.getId(), null, true, List.of(), new QuotaConfig(10, QuotaPeriod.WEEKLY),
				"someone-else");
		assertThat(quotaManager.getUsage(username)).isZero();
	}

	@Test
	void deletesQuotaWithUser() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				new QuotaConfig(500, QuotaPeriod.DAILY));

		quotaManager.checkAndIncrement(account.getUsername(), 1);

		userAccountService.deleteUser(account.getId(), "someone-else");

		assertThat(quotaDefinitionRepository.existsById(account.getUsername())).isFalse();
		assertThat(quotaManager.getLastUsed(account.getUsername())).isEmpty();
	}

	@Test
	void removesDefinitionUnderUsernameOfUserWithoutQuota() {
		var username = uniqueName();
		quotaDefinitionRepository.save(new QuotaDefinition(username, QuotaPeriod.HOURLY, 7));

		userAccountService.createUser(username, "password1", true, List.of(), null);

		assertThat(quotaDefinitionRepository.existsById(username)).isFalse();
	}

	@Test
	void syncsRedisToUsersQuotas() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				new QuotaConfig(500, QuotaPeriod.MONTHLY));
		quotaManager.checkAndIncrement(account.getUsername(), 2);
		quotaDefinitionRepository.deleteById(account.getUsername());
		var other = "no-user-" + UUID.randomUUID();
		quotaDefinitionRepository.save(new QuotaDefinition(other, QuotaPeriod.DAILY, 5));
		quotaManager.checkAndIncrement(other, 1);

		var result = userAccountService.syncQuotasToRedis();

		assertThat(result.copied()).isPositive();
		assertThat(result.removed()).isPositive();
		assertThat(result.staleUsage()).isPositive();
		// The user's usage stays; the usage of the id without a user goes
		assertThat(quotaManager.getUsage(account.getUsername())).isEqualTo(2);
		assertThat(quotaManager.getLastUsed(account.getUsername())).isPresent();
		assertThat(quotaManager.getLastUsed(other)).isEmpty();
		assertThat(quotaDefinitionRepository.findById(account.getUsername()))
				.contains(new QuotaDefinition(account.getUsername(), QuotaPeriod.MONTHLY, 500));
		assertThat(quotaDefinitionRepository.existsById(other)).isFalse();
	}

	@Test
	void validatesEntitiesWhenSaving() {
		var account = new UserAccount(uniqueName(), passwordEncoder.encode("password1"));
		account.setQuotaPeriod(QuotaPeriod.DAILY);

		assertThat(violationOf(() -> userAccountRepository.save(account)))
				.contains("Set both the period and the limit of a quota, or neither");
		assertThat(violationOf(() -> roleRepository.save(new Role("lower_case"))))
				.contains("Use letters A-Z, digits and _, starting with a letter, without ROLE_");
	}

	@Test
	void storesSettingsAndKeepsThemOnUpdateWithoutSettings() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(), null,
				new UserProfile(null, null, new UserSettings("dark", "sv")));

		userAccountService.updateUser(account.getId(), null, false, List.of(), null, "someone-else");
		assertThat(userAccountService.findSettings(account.getUsername())).isEqualTo(new UserSettings("dark", "sv"));

		userAccountService.changeSettings(account.getUsername(), settings -> settings.withLocale(null));
		assertThat(userAccountService.findSettings(account.getUsername())).isEqualTo(new UserSettings("dark", null));
		assertThat(userAccountService.findSettings("no-such-user")).isEqualTo(UserSettings.NONE);
		assertThat(violationOf(() -> userAccountService.changeSettings(account.getUsername(),
				settings -> settings.withColorScheme("sepia")))).contains("colorScheme");
	}

	@Test
	void storesNameAndKeepsItOnUpdateWithoutName() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(), null,
				new UserProfile(" Anna Andersson ", null, UserSettings.NONE));
		var username = account.getUsername();

		assertThat(userAccountService.findName(username)).isEqualTo("Anna Andersson");
		assertThat(userAccountService.findDisplayName(username)).isEqualTo("Anna Andersson");

		userAccountService.updateUser(account.getId(), null, true, List.of(), null, "someone-else");
		assertThat(userAccountService.findName(username)).isEqualTo("Anna Andersson");

		// Blank clears it, and the username is shown instead
		userAccountService.updateUser(account.getId(), null, true, List.of(), null,
				new UserProfile(" ", null, UserSettings.NONE), "someone-else");
		assertThat(userAccountService.findName(username)).isNull();
		assertThat(userAccountService.findDisplayName(username)).isEqualTo(username);
		assertThat(userAccountService.findDisplayName("no-such-user")).isEqualTo("no-such-user");
	}

	@Test
	void storesSenderIdsNormalizedAndOnlyAdminChangesThem() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(), null,
				UserProfile.NONE, List.of(" Lekab ", "+46 70-123 45 00"));
		var username = account.getUsername();
		assertThat(userAccountService.findSenderIds(username)).containsExactly("+46701234500", "Lekab");

		// Kept by an update without them, and by the user changing their own profile, settings or password
		userAccountService.updateUser(account.getId(), null, true, List.of(), null,
				new UserProfile("Anna", null, UserSettings.NONE), "someone-else");
		userAccountService.changeOwnProfile(username, new UserProfile("Anna", null, UserSettings.NONE));
		userAccountService.changeSettings(username, settings -> settings.withLocale("sv"));
		userAccountService.changeOwnPassword(username, "password1", "password2");
		assertThat(userAccountService.findSenderIds(username)).containsExactly("+46701234500", "Lekab");

		userAccountService.updateUser(account.getId(), null, true, List.of(), null, null, List.of("Shop"),
				"someone-else");
		assertThat(userAccountService.findSenderIds(username)).containsExactly("Shop");
		assertRule(Rule.SENDER_ID_INVALID, () -> userAccountService.updateUser(account.getId(), null, true,
				List.of(), null, null, List.of("Far too long"), "someone-else"));
		assertThat(userAccountService.findSenderIds(username)).containsExactly("Shop");
		assertThat(userAccountService.findSenderIds("no-such-user")).isEmpty();
	}

	@Test
	void keepsEmailAddressesUniqueWhateverTheirCase() {
		var email = uniqueName() + "@example.com";
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(), null,
				new UserProfile(null, email, UserSettings.NONE));
		assertThat(userAccountService.findProfile(account.getUsername()).email()).isEqualTo(email);

		assertRule(Rule.EMAIL_TAKEN, () -> userAccountService.createUser(uniqueName(), "password1", true, List.of(),
				null, new UserProfile(null, email.toUpperCase(Locale.ROOT), UserSettings.NONE)));
		// Saving the user with their own address again is fine
		userAccountService.updateUser(account.getId(), null, true, List.of(), null,
				new UserProfile("Anna", email, UserSettings.NONE), "someone-else");
		assertThat(violationOf(() -> userAccountService.changeOwnProfile(account.getUsername(),
				new UserProfile(null, "not an address", UserSettings.NONE)))).contains("email");
	}

	@Test
	void keepsUsernamesAndEmailAddressesApart() {
		var account = userAccountService.createUser(uniqueName(), "password1", true, List.of(), null,
				new UserProfile(null, uniqueName() + "@example.com", UserSettings.NONE));
		var email = userAccountService.findProfile(account.getUsername()).email();

		// Both log in, so neither may be the other kind of another user's
		assertRule(Rule.USERNAME_TAKEN, () -> userAccountService.createUser(email.toUpperCase(Locale.ROOT),
				"password1", true, List.of(), null));
		assertRule(Rule.EMAIL_TAKEN, () -> userAccountService.createUser(uniqueName(), "password1", true,
				List.of(), null, new UserProfile(null, account.getUsername(), UserSettings.NONE)));
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
	void hasAdminAndUserRolesFromMigrations() {
		assertThat(userAccountService.findAllRoles()).extracting(Role::getName).contains("ADMIN", "USER");
	}

	@Test
	void keepsBuiltInRoles() {
		for (var name : List.of("ADMIN", "USER")) {
			var role = userAccountService.findAllRoles().stream()
					.filter(candidate -> candidate.getName().equals(name)).findFirst().orElseThrow();

			assertRule(Rule.BUILT_IN_ROLE_DELETE, () -> userAccountService.deleteRole(role.getId()));
		}
		assertThat(userAccountService.findAllRoles()).extracting(Role::getName).contains("ADMIN", "USER");
	}

	// The message of the ConstraintViolationException that Hibernate throws, wherever it is in the causes
	private static String violationOf(Runnable save) {
		var thrown = catchThrowable(save::run);
		for (var cause = thrown; cause != null; cause = cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getMessage();
			}
		}
		throw new AssertionError("No constraint violation", thrown);
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
