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

import biz.asplund.redis.security.AccountRuleException.Rule;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

// Manages the admin UI's users and roles. The acting user cannot delete or disable their own account or take
// the ADMIN role from it, and the ADMIN role cannot be deleted, so at least one admin always remains
@Service
public class UserAccountService {

    public static final String ADMIN_ROLE = "ADMIN";
    public static final int MIN_PASSWORD_LENGTH = 8;

    // Upper case, as role names are conventionally written in hasRole and @RolesAllowed
    private static final Pattern ROLE_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;

    public UserAccountService(UserAccountRepository userAccountRepository, RoleRepository roleRepository,
            PasswordEncoder passwordEncoder) {
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional(readOnly = true)
    public List<UserAccount> findAllUsers() {
        return userAccountRepository.findAll(Sort.by("username"));
    }

    @Transactional(readOnly = true)
    public List<Role> findAllRoles() {
        return roleRepository.findAll(Sort.by("name"));
    }

    // Number of users holding each role, by role id
    @Transactional(readOnly = true)
    public Map<Long, Long> countUsersByRole() {
        return roleRepository.countUsersByRole().stream()
                .collect(Collectors.toMap(count -> (Long) count[0], count -> (Long) count[1]));
    }

    @Transactional
    public UserAccount createUser(String username, String password, boolean enabled, Collection<String> roleNames) {
        var name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new AccountRuleException(Rule.USERNAME_REQUIRED);
        }
        if (userAccountRepository.existsByUsername(name)) {
            throw new AccountRuleException(Rule.USERNAME_TAKEN);
        }
        var account = new UserAccount(name, encode(password));
        account.setEnabled(enabled);
        account.getRoles().addAll(roles(roleNames));
        return userAccountRepository.save(account);
    }

    // A null or empty password keeps the current one
    @Transactional
    public UserAccount updateUser(long id, String password, boolean enabled, Collection<String> roleNames,
            String actingUsername) {
        var account = userAccountRepository.findById(id).orElseThrow(() -> new AccountRuleException(Rule.NOT_FOUND));
        if (account.getUsername().equals(actingUsername)) {
            if (!enabled) {
                throw new AccountRuleException(Rule.OWN_ACCOUNT_DISABLE);
            }
            if (!roleNames.contains(ADMIN_ROLE)) {
                throw new AccountRuleException(Rule.OWN_ADMIN_ROLE);
            }
        }
        if (password != null && !password.isEmpty()) {
            account.setPassword(encode(password));
        }
        account.setEnabled(enabled);
        account.getRoles().clear();
        account.getRoles().addAll(roles(roleNames));
        return account;
    }

    @Transactional
    public void deleteUser(long id, String actingUsername) {
        var account = userAccountRepository.findById(id).orElseThrow(() -> new AccountRuleException(Rule.NOT_FOUND));
        if (account.getUsername().equals(actingUsername)) {
            throw new AccountRuleException(Rule.OWN_ACCOUNT_DELETE);
        }
        userAccountRepository.delete(account);
    }

    // Names are stored in upper case, without the ROLE_ prefix
    @Transactional
    public Role createRole(String name) {
        var roleName = name == null ? "" : name.trim().toUpperCase(Locale.ROOT);
        if (roleName.startsWith("ROLE_") || !ROLE_NAME.matcher(roleName).matches()) {
            throw new AccountRuleException(Rule.ROLE_NAME_INVALID);
        }
        if (roleRepository.findByName(roleName).isPresent()) {
            throw new AccountRuleException(Rule.ROLE_EXISTS);
        }
        return roleRepository.save(new Role(roleName));
    }

    // Users holding the role lose it; the database removes their rows in user_roles
    @Transactional
    public void deleteRole(long id) {
        var role = roleRepository.findById(id).orElseThrow(() -> new AccountRuleException(Rule.NOT_FOUND));
        if (ADMIN_ROLE.equals(role.getName())) {
            throw new AccountRuleException(Rule.ADMIN_ROLE_DELETE);
        }
        roleRepository.delete(role);
    }

    private String encode(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new AccountRuleException(Rule.PASSWORD_TOO_SHORT);
        }
        return passwordEncoder.encode(password);
    }

    private List<Role> roles(Collection<String> roleNames) {
        var byName = roleRepository.findAll().stream().collect(Collectors.toMap(Role::getName, Function.identity()));
        return roleNames.stream().map(name -> {
            var role = byName.get(name);
            if (role == null) {
                throw new AccountRuleException(Rule.NOT_FOUND);
            }
            return role;
        }).toList();
    }
}
