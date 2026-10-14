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

package biz.asplund.redis.quota.ui;

import biz.asplund.redis.quota.QuotaConfig;
import biz.asplund.redis.quota.QuotaDefinition;
import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.quota.QuotaPeriod;
import biz.asplund.redis.quota.QuotaStatus;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

// A quota definition with its current status per defined period, read once per refresh
record QuotaRow(QuotaDefinition definition, Map<QuotaPeriod, QuotaStatus> statuses, Optional<Instant> lastUsed) {

    static QuotaRow load(QuotaDefinition definition, QuotaManager quotaManager) {
        var statuses = new EnumMap<QuotaPeriod, QuotaStatus>(QuotaPeriod.class);
        limits(definition).forEach((period, limit) ->
                statuses.put(period, quotaManager.getStatus(definition.id(), new QuotaConfig(limit, period))));
        return new QuotaRow(definition, statuses, quotaManager.getLastUsed(definition.id()));
    }

    String id() {
        return definition.id();
    }

    Map<QuotaPeriod, Integer> limits() {
        return limits(definition);
    }

    // A definition saved without limits is read back with a null map
    private static Map<QuotaPeriod, Integer> limits(QuotaDefinition definition) {
        return definition.limits() != null ? definition.limits() : Map.of();
    }
}
