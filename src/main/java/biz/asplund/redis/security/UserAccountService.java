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
import biz.asplund.redis.sms.SmsService;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

// Manages the admin UI's users, their roles and quotas. The acting user cannot delete or disable their own
// account or take the ADMIN role from it, and the built-in ADMIN and USER roles cannot be deleted, so at least one admin always
// remains. A user's quota is kept in PostgreSQL and copied to Redis as the QuotaDefinition under the username,
// which QuotaManager reads. The copy is written inside the transaction, so a Redis error rolls back the change.
// Quotas belong to users: Redis holds no other definitions, and syncQuotasToRedis removes any that appear. A user's
// sender ids, the ones they may send SMS from, are set only by createUser and updateUser, which only the admin-only
// users view calls; the methods a user changes their own account with leave them alone
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

    // A null quota means none; the user starts without a name, email address or settings
    @Transactional
    public UserAccount createUser(String username, String password, boolean enabled, Collection<String> roleNames,
            QuotaConfig quota) {
        return createUser(username, password, enabled, roleNames, quota, UserProfile.NONE);
    }

    // The user starts without sender ids
    @Transactional
    public UserAccount createUser(String username, String password, boolean enabled, Collection<String> roleNames,
            QuotaConfig quota, UserProfile profile) {
        return createUser(username, password, enabled, roleNames, quota, profile, Set.of());
    }

    // The sender ids are normalized as SmsService.normalizeSenderId does
    @Transactional
    public UserAccount createUser(String username, String password, boolean enabled, Collection<String> roleNames,
            QuotaConfig quota, UserProfile profile, Collection<String> senderIds) {
        var trimmedUsername = username == null ? "" : username.trim();
        if (trimmedUsername.isEmpty()) {
            throw new AccountRuleException(Rule.USERNAME_REQUIRED);
        }
        // Another user's email address would be a second way to log in as either of them
        if (userAccountRepository.existsByUsername(trimmedUsername)
                || userAccountRepository.findByEmailIgnoreCase(trimmedUsername).isPresent()) {
            throw new AccountRuleException(Rule.USERNAME_TAKEN);
        }
        validate(quota);
        var account = new UserAccount(trimmedUsername, encode(password));
        account.setEnabled(enabled);
        account.getRoles().addAll(roles(roleNames));
        account.setQuota(quota);
        checkEmailFree(profile.email(), trimmedUsername);
        account.setProfile(profile);
        account.setSenderIds(normalizeSenderIds(senderIds));
        var saved = userAccountRepository.save(account);
        copyQuota(saved);
        return saved;
    }

    // A null or empty password keeps the current one; a null quota removes it. The profile is kept
    @Transactional
    public UserAccount updateUser(long id, String password, boolean enabled, Collection<String> roleNames,
            QuotaConfig quota, String actingUsername) {
        return updateUser(id, password, enabled, roleNames, quota, null, actingUsername);
    }

    // As above, with the profile, where a null profile keeps the current one. The sender ids are kept
    @Transactional
    public UserAccount updateUser(long id, String password, boolean enabled, Collection<String> roleNames,
            QuotaConfig quota, UserProfile profile, String actingUsername) {
        return updateUser(id, password, enabled, roleNames, quota, profile, null, actingUsername);
    }

    // As above, with the sender ids, normalized as SmsService.normalizeSenderId does, where null keeps the current ones
    @Transactional
    public UserAccount updateUser(long id, String password, boolean enabled, Collection<String> roleNames,
            QuotaConfig quota, UserProfile profile, Collection<String> senderIds, String actingUsername) {
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
        var normalizedSenderIds = senderIds == null ? null : normalizeSenderIds(senderIds);
        if (password != null && !password.isEmpty()) {
            account.setPassword(encode(password));
        }
        account.setEnabled(enabled);
        account.getRoles().clear();
        account.getRoles().addAll(roles(roleNames));
        account.setQuota(quota);
        if (profile != null) {
            checkEmailFree(profile.email(), account.getUsername());
            account.setProfile(profile);
        }
        if (normalizedSenderIds != null) {
            account.setSenderIds(normalizedSenderIds);
        }
        copyQuota(account);
        return account;
    }

    // The quota of the user with the username, or empty when the user has none or does not exist
    @Transactional(readOnly = true)
    public Optional<QuotaConfig> findQuota(String username) {
        return userAccountRepository.findByUsername(username).flatMap(UserAccount::getQuota);
    }

    // The sender ids the user with the username may send SMS from, sorted, or none when no user has the username
    @Transactional(readOnly = true)
    public List<String> findSenderIds(String username) {
        return userAccountRepository.findByUsername(username)
                .map(account -> account.getSenderIds().stream().sorted().toList()).orElse(List.of());
    }

    // The name people read for the user with the username: their name, or else the username itself
    @Transactional(readOnly = true)
    public String findDisplayName(String username) {
        return userAccountRepository.findByUsername(username).map(UserAccount::getDisplayName).orElse(username);
    }

    // The name of the user with the username, or null when not set or no user has the username
    @Transactional(readOnly = true)
    public String findName(String username) {
        return userAccountRepository.findByUsername(username).map(UserAccount::getName).orElse(null);
    }

    // Changes the profile of the user with the username, as the user does on their profile page
    @Transactional
    public void changeOwnProfile(String username, UserProfile profile) {
        var account = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new AccountRuleException(Rule.NOT_FOUND));
        checkEmailFree(profile.email(), username);
        account.setProfile(profile);
    }

    // The profile of the user with the username, or none for a name no user has
    @Transactional(readOnly = true)
    public UserProfile findProfile(String username) {
        return userAccountRepository.findByUsername(username).map(UserAccount::getProfile).orElse(UserProfile.NONE);
    }

    // The settings of the user with the username, or none for a name no user has
    @Transactional(readOnly = true)
    public UserSettings findSettings(String username) {
        return userAccountRepository.findByUsername(username).map(UserAccount::getSettings).orElse(UserSettings.NONE);
    }

    // Changes the settings of the user with the username, as the toolbar does for the logged-in user; a name no
    // user has, such as one from spring.security.user before it is stored, is left alone
    @Transactional
    public void changeSettings(String username, UnaryOperator<UserSettings> change) {
        userAccountRepository.findByUsername(username)
                .ifPresent(account -> account.setSettings(change.apply(account.getSettings())));
    }

    // Changes the password of the user with the username, as the user does on their profile page, which takes
    // their current password to confirm it is them
    @Transactional
    public void changeOwnPassword(String username, String currentPassword, String newPassword) {
        var account = userAccountRepository.findByUsername(username)
                .orElseThrow(() -> new AccountRuleException(Rule.NOT_FOUND));
        if (currentPassword == null || !passwordEncoder.matches(currentPassword, account.getPassword())) {
            throw new AccountRuleException(Rule.WRONG_PASSWORD);
        }
        account.setPassword(encode(newPassword));
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

    // Another user's address, whatever its case, is refused; the database's unique index backs this up. So is
    // another user's username, as both log in. Checked before the user is changed, as the queries would otherwise
    // first flush the change and fail on the index
    private void checkEmailFree(String email, String username) {
        if (email == null || email.isBlank()) {
            return;
        }
        var address = email.trim();
        if (userAccountRepository.findByEmailIgnoreCase(address)
                .or(() -> userAccountRepository.findByUsernameIgnoreCase(address))
                .filter(other -> !other.getUsername().equals(username)).isPresent()) {
            throw new AccountRuleException(Rule.EMAIL_TAKEN);
        }
    }

    private static Set<String> normalizeSenderIds(Collection<String> senderIds) {
        return senderIds.stream().map(senderId -> SmsService.normalizeSenderId(senderId)
                        .orElseThrow(() -> new AccountRuleException(Rule.SENDER_ID_INVALID)))
                .collect(Collectors.toSet());
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
