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

import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Logs in the users stored in the users table, with their roles from user_roles. Being a UserDetailsService
// bean, it replaces Spring Boot's in-memory user from spring.security.user.*, which AdminAccountInitializer
// stores here instead
@Service
public class DatabaseUserDetailsService implements UserDetailsService {

    private final UserAccountRepository userAccountRepository;

    public DatabaseUserDetailsService(UserAccountRepository userAccountRepository) {
        this.userAccountRepository = userAccountRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String username) {
        // The login can be the username or the email address, whatever its case; the user is logged in under the
        // username either way, which is what quotas and settings are kept under. UserAccountService keeps the two
        // from meeting, so no login can mean two users
        var account = userAccountRepository.findByUsername(username)
                .or(() -> userAccountRepository.findByEmailIgnoreCase(username.trim()))
                .orElseThrow(() -> new UsernameNotFoundException("No user " + username));
        return User.withUsername(account.getUsername())
                .password(account.getPassword())
                .disabled(!account.isEnabled())
                .roles(account.getRoles().stream().map(Role::getName).toArray(String[]::new))
                .build();
    }
}
