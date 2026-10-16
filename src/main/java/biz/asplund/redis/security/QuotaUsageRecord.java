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
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

/// The final usage of one finished period of a user's quota, which [QuotaHistoryService] records.
@Entity
@Table(name = "quota_usage_history")
public class QuotaUsageRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @NotNull
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id")
    private UserAccount user;

    @NotNull
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private QuotaPeriod period;

    @NotNull
    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @PositiveOrZero
    @Column(nullable = false)
    private int used;

    @PositiveOrZero
    @Column(name = "quota_limit", nullable = false)
    private int quotaLimit;

    /// For JPA.
    protected QuotaUsageRecord() {
    }

    /// A record of the user's period that starts at `periodStart`, with no usage until
    /// [#update(int, int)] sets it.
    public QuotaUsageRecord(UserAccount user, QuotaPeriod period, Instant periodStart) {
        this.user = user;
        this.period = period;
        this.periodStart = periodStart;
    }

    /// Returns the period of the user's quota at the time it was recorded.
    public QuotaPeriod getPeriod() {
        return period;
    }

    /// Returns when the period started.
    public Instant getPeriodStart() {
        return periodStart;
    }

    /// Returns the final count of the period.
    public int getUsed() {
        return used;
    }

    /// Returns the limit of the user's quota when the period was last recorded.
    public int getQuotaLimit() {
        return quotaLimit;
    }

    /// Sets the final count of the period and the limit of the user's quota, each time the period is recorded.
    public void update(int used, int quotaLimit) {
        this.used = used;
        this.quotaLimit = quotaLimit;
    }
}
