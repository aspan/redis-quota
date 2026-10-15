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

import biz.asplund.redis.quota.QuotaManager;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.List;

// Records the final usage of each user's finished periods in PostgreSQL, from the counters in Redis, which expire
// an hour after their period ends. UserQuotaSync runs it hourly and before it deletes stale counters
@Service
@EnableConfigurationProperties(QuotaHistoryProperties.class)
public class QuotaHistoryService {

    private final UserAccountRepository userAccountRepository;
    private final QuotaUsageRecordRepository quotaUsageRecordRepository;
    private final QuotaManager quotaManager;
    private final QuotaHistoryProperties properties;
    private final Clock clock;

    public QuotaHistoryService(UserAccountRepository userAccountRepository,
            QuotaUsageRecordRepository quotaUsageRecordRepository, QuotaManager quotaManager,
            QuotaHistoryProperties properties, Clock clock) {
        this.userAccountRepository = userAccountRepository;
        this.quotaUsageRecordRepository = quotaUsageRecordRepository;
        this.quotaManager = quotaManager;
        this.properties = properties;
        this.clock = clock;
    }

    // Records each user's previous period, with the current limit; recording a period again updates its row.
    // Periods without a counter, because nothing was counted, get no row. Returns how many periods it recorded
    @Transactional
    public int recordPreviousPeriods() {
        var recorded = 0;
        for (var user : userAccountRepository.findAll()) {
            var quota = user.getQuota();
            if (quota.isEmpty()) {
                continue;
            }
            var usage = quotaManager.getPreviousPeriodUsage(user.getUsername(), quota.get().period());
            if (usage.isPresent()) {
                var record = quotaUsageRecordRepository
                        .findByUserAndPeriodAndPeriodStart(user, quota.get().period(), usage.get().start())
                        .orElseGet(() -> new QuotaUsageRecord(user, quota.get().period(), usage.get().start()));
                record.update(usage.get().used(), quota.get().limit());
                quotaUsageRecordRepository.save(record);
                recorded++;
            }
        }
        return recorded;
    }

    // Deletes the periods that started longer ago than quota.history.retention; returns how many
    @Transactional
    public int deleteExpired() {
        return quotaUsageRecordRepository.deleteByPeriodStartBefore(clock.instant().minus(properties.retention()));
    }

    // The recorded periods of the user with the username, which is also the id of their quota, newest first
    @Transactional(readOnly = true)
    public List<QuotaUsageRecord> findHistory(String username) {
        return quotaUsageRecordRepository.findByUserUsernameOrderByPeriodStartDesc(username);
    }
}
