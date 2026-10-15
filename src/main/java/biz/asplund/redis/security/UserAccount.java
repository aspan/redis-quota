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

    @Column(nullable = false, unique = true, length = 100)
    private String username;

    // Encoded, with the encoding id prefix of the DelegatingPasswordEncoder
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

    @Column(name = "quota_limit")
    private Integer quotaLimit;

    protected UserAccount() {
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

    public Optional<QuotaConfig> getQuota() {
        return quotaPeriod == null ? Optional.empty() : Optional.of(new QuotaConfig(quotaLimit, quotaPeriod));
    }

    // Null removes the quota
    public void setQuota(QuotaConfig quota) {
        this.quotaPeriod = quota == null ? null : quota.period();
        this.quotaLimit = quota == null ? null : quota.limit();
    }
}
