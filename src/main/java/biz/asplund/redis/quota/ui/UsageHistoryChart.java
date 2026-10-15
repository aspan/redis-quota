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

import biz.asplund.redis.security.QuotaUsageRecord;
import com.vaadin.flow.component.AttachEvent;
import org.vaadin.addons.chartjs.ChartJs;
import org.vaadin.addons.chartjs.config.BarChartConfig;
import org.vaadin.addons.chartjs.data.BarDataset;
import org.vaadin.addons.chartjs.data.LineDataset;
import org.vaadin.addons.chartjs.options.scale.Axis;
import org.vaadin.addons.chartjs.options.scale.LinearScale;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;

// The final usage of a user's latest finished periods as a bar chart, oldest on the left, with the limit at the
// time as a dashed line. Bars are colored as the usage badges: red when the limit was reached, orange when less
// than 20% was left, green otherwise. Chart.js draws on a canvas, so the colors are fixed rather than theme colors
class UsageHistoryChart extends ChartJs {

    // More bars would get too thin for the editor
    static final int MAX_PERIODS = 30;

    private static final String GREEN = "rgba(46, 158, 91, 0.8)";
    private static final String ORANGE = "rgba(232, 145, 45, 0.8)";
    private static final String RED = "rgba(214, 69, 69, 0.8)";
    private static final String LIMIT = "rgba(128, 128, 128, 0.9)";

    private final ZoneId zone;
    private final Locale locale;

    // The records newest first, as QuotaHistoryService returns them
    UsageHistoryChart(List<QuotaUsageRecord> records, ZoneId zone, Locale locale) {
        this.zone = zone;
        this.locale = locale;

        var shown = records.subList(0, Math.min(MAX_PERIODS, records.size())).reversed();
        var config = new BarChartConfig();
        config.data()
                .labelsAsList(shown.stream().map(this::label).toList())
                .addDataset(new BarDataset().type().label(getTranslation("history.used"))
                        .dataAsList(shown.stream().map(record -> (double) record.getUsed()).toList())
                        .backgroundColor(shown.stream().map(UsageHistoryChart::color).toArray(String[]::new)))
                .addDataset(new LineDataset().type().label(getTranslation("history.limit"))
                        .dataAsList(shown.stream().map(record -> (double) record.getQuotaLimit()).toList())
                        .fill(false).borderColor(LIMIT).borderDash(4, 4).borderWidth(2).pointRadius(0))
                .and()
                .options()
                .responsive(true)
                // Fill the height set on the component instead of keeping Chart.js's aspect ratio
                .maintainAspectRatio(false)
                .scales().add(Axis.Y, new LinearScale().ticks().beginAtZero(true).and()).and()
                .done();
        configure(config);
        setWidthFull();
        setHeight("16rem");
    }

    // Replaces the add-on's own setup, which looks the canvas up by id with document.getElementById as soon as the
    // component is attached. In the editor, which MasterDetailLayout puts into the page only after that, the lookup
    // fails and no chart is drawn. This setup runs on the chart's own element and waits until it is in the page.
    // The chart is kept on the element as the add-on keeps it, so its update and destroy still find it
    @Override
    protected void onAttach(AttachEvent attachEvent) {
        getElement().executeJs("""
                const element = this;
                const config = JSON.parse($0);
                const create = () => {
                    if (!element.isConnected) {
                        requestAnimationFrame(create);
                        return;
                    }
                    element.chartjs?.destroy();
                    element.chartjs = new Chart(element.querySelector('canvas').getContext('2d'), config);
                };
                create();
                """, getConfig().buildJson().toJson());
    }

    private static String color(QuotaUsageRecord record) {
        if (record.getUsed() >= record.getQuotaLimit()) {
            return RED;
        }
        return record.getQuotaLimit() - record.getUsed() < record.getQuotaLimit() * 0.2 ? ORANGE : GREEN;
    }

    // The period in the UI language, as precise as its length needs
    String label(QuotaUsageRecord record) {
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
