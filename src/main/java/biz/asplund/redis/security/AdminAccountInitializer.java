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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.security.autoconfigure.SecurityProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Stores the user of spring.security.user.* in the database at startup, if it is not there yet, so a first
// admin can log in. Nothing is stored unless the password is set; Spring Boot's generated password is never
// shown once the users come from the database. An existing user is left as it is
@Component
public class AdminAccountInitializer implements ApplicationRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAccountInitializer.class);

    private final SecurityProperties securityProperties;
    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public AdminAccountInitializer(SecurityProperties securityProperties, UserAccountRepository userAccountRepository,
            RoleRepository roleRepository, PasswordEncoder passwordEncoder) {
        this.securityProperties = securityProperties;
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        var user = securityProperties.getUser();
        if (user.isPasswordGenerated() || userAccountRepository.existsByUsername(user.getName())) {
            return;
        }
        var account = new UserAccount(user.getName(), passwordEncoder.encode(user.getPassword()));
        for (var roleName : user.getRoles()) {
            account.getRoles().add(roleRepository.findByName(roleName)
                    .orElseGet(() -> roleRepository.save(new Role(roleName))));
        }
        userAccountRepository.save(account);
        LOGGER.info("Created user {} with roles {} from spring.security.user", user.getName(), user.getRoles());
    }
}
