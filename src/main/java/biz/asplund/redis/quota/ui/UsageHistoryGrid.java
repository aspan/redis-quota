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

import biz.asplund.redis.quota.QuotaStatus;
import biz.asplund.redis.security.QuotaUsageRecord;
import com.vaadin.flow.component.grid.ColumnTextAlign;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.data.renderer.ComponentRenderer;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

// The final usage of a user's finished periods, newest first, with the usage colored as in the quotas list
class UsageHistoryGrid extends Grid<QuotaUsageRecord> {

    private final ZoneId zone;
    private final Locale locale;

    UsageHistoryGrid(List<QuotaUsageRecord> records, ZoneId zone, Locale locale) {
        this.zone = zone;
        this.locale = locale;

        addColumn(this::label)
                .setHeader(getTranslation("history.column.period")).setFlexGrow(1);
        addColumn(new ComponentRenderer<>(record -> QuotaView.usageBadge(new QuotaStatus(
                record.getUsed() < record.getQuotaLimit(), record.getQuotaLimit(), record.getUsed(),
                Math.max(0, record.getQuotaLimit() - record.getUsed()), record.getPeriodStart()))))
                .setHeader(getTranslation("history.column.usage")).setTextAlign(ColumnTextAlign.END)
                .setAutoWidth(true).setFlexGrow(0);
        setItems(records);
        setEmptyStateText(getTranslation("history.empty"));
        setWidthFull();
        setHeight("16rem");
    }

    // The period in the UI language, as precise as its length needs
    private String label(QuotaUsageRecord record) {
        var start = record.getPeriodStart().atZone(zone);
        return switch (record.getPeriod()) {
            case HOURLY -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT).withLocale(locale).format(start);
            case DAILY -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(start);
            case WEEKLY -> getTranslation("history.week",
                    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(start));
            case MONTHLY -> DateTimeFormatter.ofPattern("LLLL yyyy", locale).format(start);
            case YEARLY -> String.valueOf(start.getYear());
        };
    }
}
