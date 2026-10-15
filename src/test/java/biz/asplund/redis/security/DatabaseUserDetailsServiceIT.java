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

import biz.asplund.testcontainers.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Users and roles in PostgreSQL, as Spring Security reads them.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "spring.security.user.password=secret")
class DatabaseUserDetailsServiceIT {

	@Autowired
	private DatabaseUserDetailsService userDetailsService;

	@Autowired
	private UserAccountRepository userAccountRepository;

	@Autowired
	private RoleRepository roleRepository;

	@Autowired
	private PasswordEncoder passwordEncoder;

	@Autowired
	private AdminAccountInitializer adminAccountInitializer;

	@Test
	void storesConfiguredAdminAtStartup() {
		var admin = userDetailsService.loadUserByUsername("admin");

		assertThat(admin.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_ADMIN");
		assertThat(admin.getPassword()).startsWith("{bcrypt}");
		assertThat(passwordEncoder.matches("secret", admin.getPassword())).isTrue();
		assertThat(admin.isEnabled()).isTrue();
	}

	@Test
	void leavesExistingAdminAsItIs() {
		var admin = userAccountRepository.findByUsername("admin").orElseThrow();
		var password = admin.getPassword();

		adminAccountInitializer.run(new DefaultApplicationArguments());

		assertThat(userAccountRepository.findByUsername("admin").orElseThrow().getPassword()).isEqualTo(password);
		assertThat(userAccountRepository.findAll()).filteredOn(account -> account.getUsername().equals("admin"))
				.hasSize(1);
	}

	@Test
	void loadsRolesOfUser() {
		var username = uniqueUsername();
		var account = new UserAccount(username, passwordEncoder.encode("pw"));
		account.getRoles().add(roleRepository.findByName("ADMIN").orElseThrow());
		// Role names are upper case letters, digits and _
		var roleName = "AUDITOR_" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.ROOT);
		account.getRoles().add(roleRepository.save(new Role(roleName)));
		userAccountRepository.save(account);

		var user = userDetailsService.loadUserByUsername(username);

		assertThat(user.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactlyInAnyOrder("ROLE_ADMIN", "ROLE_" + roleName);
	}

	@Test
	void loadsDisabledUserAsDisabled() {
		var username = uniqueUsername();
		var account = new UserAccount(username, passwordEncoder.encode("pw"));
		account.setEnabled(false);
		userAccountRepository.save(account);

		assertThat(userDetailsService.loadUserByUsername(username).isEnabled()).isFalse();
	}

	@Test
	void rejectsUnknownUser() {
		assertThatExceptionOfType(UsernameNotFoundException.class)
				.isThrownBy(() -> userDetailsService.loadUserByUsername(uniqueUsername()));
	}

	private static String uniqueUsername() {
		return "user-" + UUID.randomUUID();
	}
}
