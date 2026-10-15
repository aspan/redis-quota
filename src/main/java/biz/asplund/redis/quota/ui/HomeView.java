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

import biz.asplund.redis.quota.QuotaManager;
import biz.asplund.redis.security.QuotaHistoryService;
import biz.asplund.redis.security.UserAccountService;
import com.vaadin.flow.component.html.H4;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.i18n.LocaleChangeEvent;
import com.vaadin.flow.i18n.LocaleChangeObserver;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Menu;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.spring.security.AuthenticationContext;
import jakarta.annotation.security.PermitAll;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

// The page every logged-in user lands on: their own quota, its usage in the current period, read-only, and its
// usage history
@Route("")
@PageTitle("home.title")
@Menu(title = "home.title", icon = "vaadin:home", order = 0)
@PermitAll
public class HomeView extends VerticalLayout implements BeforeEnterObserver, LocaleChangeObserver {

    private final UserAccountService userAccountService;
    private final QuotaManager quotaManager;
    private final QuotaHistoryService quotaHistoryService;
    private final ZoneId zone;
    private final String username;

    public HomeView(UserAccountService userAccountService, QuotaManager quotaManager,
            QuotaHistoryService quotaHistoryService, Clock clock, AuthenticationContext authenticationContext) {
        this.userAccountService = userAccountService;
        this.quotaManager = quotaManager;
        this.quotaHistoryService = quotaHistoryService;
        this.zone = clock.getZone();
        this.username = authenticationContext.getPrincipalName().orElse("");
        setMaxWidth("40rem");
    }

    // On every navigation, including to this view when it is already shown, so the usage is current
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        build();
    }

    // Called when attached and whenever the language changes
    @Override
    public void localeChange(LocaleChangeEvent event) {
        build();
    }

    // Reads the quota again each time
    private void build() {
        removeAll();
        var dateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
                .withLocale(getLocale()).withZone(zone);
        try {
            add(new Span(getTranslation("home.welcome", userAccountService.findDisplayName(username))),
                    new H4(getTranslation("home.quota")));
            var quota = userAccountService.findQuota(username);
            if (quota.isEmpty()) {
                add(new Span(getTranslation("home.noQuota")));
                return;
            }
            add(new Span(getTranslation("home.quotaHint")),
                    new QuotaUsageCard(username, quota.get().period(), quotaManager.getStatus(username),
                            quotaManager.getLastUsed(username), quotaManager, dateTimeFormatter, null),
                    new UsageHistorySection(quotaHistoryService, username, dateTimeFormatter));
        } catch (IllegalArgumentException | DataAccessException e) {
            // The copy in Redis may be missing, or PostgreSQL out of reach
            var cause = e instanceof DataAccessException dataAccess ? dataAccess.getMostSpecificCause() : e;
            add(new Span(getTranslation("home.loadFailed", cause.getMessage())));
        }
    }
}
