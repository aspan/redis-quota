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
import biz.asplund.redis.security.AccountRuleException.Rule;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

// Manages the admin UI's users, their roles and quotas. The acting user cannot delete or disable their own
// account or take the ADMIN role from it, and the built-in ADMIN and USER roles cannot be deleted, so at least one admin always
// remains. A user's quota is kept in PostgreSQL and copied to Redis as the QuotaDefinition under the username,
// which QuotaManager reads. The copy is written inside the transaction, so a Redis error rolls back the change.
// Quotas belong to users: Redis holds no other definitions, and syncQuotasToRedis removes any that appear
@Service
public class UserAccountService {

    public static final String ADMIN_ROLE = "ADMIN";
    public static final String USER_ROLE = "USER";
    // The roles the migrations create, which cannot be deleted: the views require ADMIN, and USER is the role
    // for users who are not admins
    public static final Set<String> BUILT_IN_ROLES = Set.of(ADMIN_ROLE, USER_ROLE);
    public static final int MIN_PASSWORD_LENGTH = 8;

    // Upper case, as role names are conventionally written in hasRole and @RolesAllowed
    private static final Pattern ROLE_NAME = Pattern.compile("[A-Z][A-Z0-9_]{0,49}");

    private final UserAccountRepository userAccountRepository;
    private final RoleRepository roleRepository;
    private final PasswordEncoder passwordEncoder;
    private final QuotaDefinitionRepository quotaDefinitionRepository;
    private final QuotaManager quotaManager;

    public UserAccountService(UserAccountRepository userAccountRepository, RoleRepository roleRepository,
            PasswordEncoder passwordEncoder, QuotaDefinitionRepository quotaDefinitionRepository,
            QuotaManager quotaManager) {
        this.userAccountRepository = userAccountRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.quotaDefinitionRepository = quotaDefinitionRepository;
        this.quotaManager = quotaManager;
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

    // Usernames of the users with a quota, whose QuotaDefinitions this service owns
    @Transactional(readOnly = true)
    public Set<String> findQuotaUsernames() {
        return userAccountRepository.findAll().stream().filter(account -> account.getQuota().isPresent())
                .map(UserAccount::getUsername).collect(Collectors.toSet());
    }

    // A null quota means none
    @Transactional
    public UserAccount createUser(String username, String password, boolean enabled, Collection<String> roleNames,
            QuotaConfig quota) {
        var name = username == null ? "" : username.trim();
        if (name.isEmpty()) {
            throw new AccountRuleException(Rule.USERNAME_REQUIRED);
        }
        if (userAccountRepository.existsByUsername(name)) {
            throw new AccountRuleException(Rule.USERNAME_TAKEN);
        }
        validate(quota);
        var account = new UserAccount(name, encode(password));
        account.setEnabled(enabled);
        account.getRoles().addAll(roles(roleNames));
        account.setQuota(quota);
        var saved = userAccountRepository.save(account);
        copyQuota(saved);
        return saved;
    }

    // A null or empty password keeps the current one; a null quota removes it
    @Transactional
    public UserAccount updateUser(long id, String password, boolean enabled, Collection<String> roleNames,
            QuotaConfig quota, String actingUsername) {
        var account = userAccountRepository.findById(id).orElseThrow(() -> new AccountRuleException(Rule.NOT_FOUND));
        if (account.getUsername().equals(actingUsername)) {
            if (!enabled) {
                throw new AccountRuleException(Rule.OWN_ACCOUNT_DISABLE);
            }
            if (!roleNames.contains(ADMIN_ROLE)) {
                throw new AccountRuleException(Rule.OWN_ADMIN_ROLE);
            }
        }
        validate(quota);
        if (password != null && !password.isEmpty()) {
            account.setPassword(encode(password));
        }
        account.setEnabled(enabled);
        account.getRoles().clear();
        account.getRoles().addAll(roles(roleNames));
        account.setQuota(quota);
        copyQuota(account);
        return account;
    }

    @Transactional
    public void deleteUser(long id, String actingUsername) {
        var account = userAccountRepository.findById(id).orElseThrow(() -> new AccountRuleException(Rule.NOT_FOUND));
        if (account.getUsername().equals(actingUsername)) {
            throw new AccountRuleException(Rule.OWN_ACCOUNT_DELETE);
        }
        userAccountRepository.delete(account);
        removeQuota(account.getUsername());
    }

    // How many definitions syncQuotasToRedis wrote and removed, and how many stale usage keys it deleted
    public record SyncResult(int copied, int removed, int staleUsage) {
    }

    // Makes Redis match the users' quotas: writes every user's quota again, for when Redis has lost them, removes
    // every definition that is not a user's quota, and deletes stale usage: the counters and last-used times of
    // ids that are not users with a quota, and counters of periods the users' quotas no longer have
    @Transactional(readOnly = true)
    public SyncResult syncQuotasToRedis() {
        var definitions = userAccountRepository.findAll().stream()
                .flatMap(account -> account.getQuota().stream()
                        .map(quota -> new QuotaDefinition(account.getUsername(), quota.period(), quota.limit())))
                .toList();
        quotaDefinitionRepository.saveAll(definitions);
        var userQuotaIds = definitions.stream().map(QuotaDefinition::id).collect(Collectors.toSet());
        var others = StreamSupport.stream(quotaDefinitionRepository.findAll().spliterator(), false)
                .filter(Objects::nonNull).map(QuotaDefinition::id).filter(id -> !userQuotaIds.contains(id)).toList();
        quotaDefinitionRepository.deleteAllById(others);
        var staleUsage = quotaManager.deleteStaleUsage(definitions.stream()
                .collect(Collectors.toMap(QuotaDefinition::id, QuotaDefinition::period)));
        return new SyncResult(definitions.size(), others.size(), staleUsage);
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
        if (BUILT_IN_ROLES.contains(role.getName())) {
            throw new AccountRuleException(Rule.BUILT_IN_ROLE_DELETE);
        }
        roleRepository.delete(role);
    }

    private static void validate(QuotaConfig quota) {
        if (quota != null && (quota.period() == null || quota.limit() < 0)) {
            throw new AccountRuleException(Rule.QUOTA_LIMIT_INVALID);
        }
    }

    // Saves the user's quota as their QuotaDefinition, or removes the quota when the user has none
    private void copyQuota(UserAccount account) {
        account.getQuota().ifPresentOrElse(
                quota -> quotaDefinitionRepository.save(
                        new QuotaDefinition(account.getUsername(), quota.period(), quota.limit())),
                () -> removeQuota(account.getUsername()));
    }

    // Deletes the definition and the usage counted under the username, so a quota given later starts from zero
    private void removeQuota(String username) {
        quotaDefinitionRepository.deleteById(username);
        quotaManager.deleteUsage(username);
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
