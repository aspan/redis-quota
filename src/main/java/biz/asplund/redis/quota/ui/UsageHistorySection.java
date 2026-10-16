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

import biz.asplund.redis.security.QuotaHistoryService;
import biz.asplund.redis.security.QuotaUsageRecord;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import org.springframework.dao.DataAccessException;

import java.time.format.DateTimeFormatter;
import java.util.List;

/// The usage history of one user's quota, under its heading: the chart, or a text when no period is recorded yet
/// or the history cannot be read.
///
/// Shown in the user editor and the quota editor.
class UsageHistorySection extends VerticalLayout {

    private final boolean hasHistory;

    /// Reads the history of the user and creates the section.
    ///
    /// @param username          the user whose history is shown
    /// @param dateTimeFormatter gives the time zone and language the periods are shown in
    UsageHistorySection(QuotaHistoryService quotaHistoryService, String username,
                        DateTimeFormatter dateTimeFormatter) {
        setPadding(false);
        setWidthFull();
        add(new H4(getTranslation("history.title")));

        List<QuotaUsageRecord> records;
        try {
            records = quotaHistoryService.findHistory(username);
        } catch (DataAccessException e) {
            hasHistory = false;
            add(new Span(getTranslation("history.loadFailed", e.getMostSpecificCause().getMessage())));
            return;
        }
        hasHistory = !records.isEmpty();
        add(hasHistory
                ? new UsageHistoryChart(records, dateTimeFormatter.getZone(), dateTimeFormatter.getLocale())
                : new Span(getTranslation("history.empty")));
    }

    /// Whether any finished period is recorded.
    boolean hasHistory() {
        return hasHistory;
    }
}
