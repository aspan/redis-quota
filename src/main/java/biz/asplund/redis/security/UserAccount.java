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
import biz.asplund.redis.quota.QuotaPeriod;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

// A login of the admin UI; named UserAccount so it does not clash with Spring Security's User
@Entity
@Table(name = "users")
public class UserAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotBlank(message = "{userAccount.username.notBlank}")
    @Size(max = 100, message = "{userAccount.username.size}")
    @Column(nullable = false, unique = true, length = 100)
    private String username;

    // The name people read, or null when not set
    @Size(max = 200, message = "{userAccount.name.size}")
    @Column(length = 200)
    private String name;

    // Unique whatever its case, or null when not set
    @Email(message = "{userAccount.email.invalid}")
    @Size(max = 254, message = "{userAccount.email.size}")
    @Column(length = 254)
    private String email;

    // Encoded, with the encoding id prefix of the DelegatingPasswordEncoder
    @NotBlank(message = "{userAccount.password.notBlank}")
    @Column(nullable = false)
    private String password;

    @Column(nullable = false)
    private boolean enabled = true;

    // A user has few roles and every login needs them, so they are loaded with the user
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "user_roles", joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();

    // The user's quota, or both null for none; Redis holds a copy as the QuotaDefinition under the username
    @Enumerated(EnumType.STRING)
    @Column(name = "quota_period", length = 10)
    private QuotaPeriod quotaPeriod;

    @PositiveOrZero(message = "{userAccount.quotaLimit.positiveOrZero}")
    @Column(name = "quota_limit")
    private Integer quotaLimit;

    // The appearance and language the user picked, or null when not set; see UserSettings
    @Pattern(regexp = "system|light|dark")
    @Column(name = "color_scheme", length = 10)
    private String colorScheme;

    @Size(max = 35)
    @Column(name = "locale", length = 35)
    private String locale;

    // An empty user, for forms to write into
    public UserAccount() {
    }

    public UserAccount(String username, String password) {
        this.username = username;
        this.password = password;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getName() {
        return name;
    }

    // Blank is stored as not set
    public void setName(String name) {
        this.name = name == null || name.isBlank() ? null : name.trim();
    }

    // The name, or the username when no name is set
    public String getDisplayName() {
        return name != null ? name : username;
    }

    public String getEmail() {
        return email;
    }

    // Blank is stored as not set
    public void setEmail(String email) {
        this.email = email == null || email.isBlank() ? null : email.trim();
    }

    public UserProfile getProfile() {
        return new UserProfile(name, email, getSettings());
    }

    public void setProfile(UserProfile profile) {
        setName(profile.name());
        setEmail(profile.email());
        setSettings(profile.settings());
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Set<Role> getRoles() {
        return roles;
    }

    public void setRoles(Set<Role> roles) {
        this.roles = new HashSet<>(roles);
    }

    public QuotaPeriod getQuotaPeriod() {
        return quotaPeriod;
    }

    public void setQuotaPeriod(QuotaPeriod quotaPeriod) {
        this.quotaPeriod = quotaPeriod;
    }

    public Integer getQuotaLimit() {
        return quotaLimit;
    }

    public void setQuotaLimit(Integer quotaLimit) {
        this.quotaLimit = quotaLimit;
    }

    public UserSettings getSettings() {
        return new UserSettings(colorScheme, locale);
    }

    public void setSettings(UserSettings settings) {
        this.colorScheme = settings.colorScheme();
        this.locale = settings.locale();
    }

    // As the users_quota_complete check in the database: both the period and the limit, or neither
    @AssertTrue(message = "{userAccount.quota.complete}")
    public boolean isQuotaComplete() {
        return (quotaPeriod == null) == (quotaLimit == null);
    }

    public Optional<QuotaConfig> getQuota() {
        return quotaPeriod == null ? Optional.empty() : Optional.of(new QuotaConfig(quotaLimit, quotaPeriod));
    }

    // Null removes the quota
    public void setQuota(QuotaConfig quota) {
        this.quotaPeriod = quota == null ? null : quota.period();
        this.quotaLimit = quota == null ? null : quota.limit();
    }
}
