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

import biz.asplund.redis.security.UserAccountService;
import biz.asplund.redis.security.UserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.function.UnaryOperator;

// The settings of the logged-in user, for LanguagePreference and ColorSchemePreference. Read from the security
// context, so it also works while a page is being sent, before any UI exists. A database error is logged and
// treated as no settings, so the page still loads with the browser's cookie or language
@Component
class LoggedInUserSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggedInUserSettings.class);

    private final UserAccountService userAccountService;

    LoggedInUserSettings(UserAccountService userAccountService) {
        this.userAccountService = userAccountService;
    }

    UserSettings get() {
        return username().map(username -> {
            try {
                return userAccountService.findSettings(username);
            } catch (DataAccessException e) {
                LOGGER.error("Could not read the settings of user {}", username, e);
                return UserSettings.NONE;
            }
        }).orElse(UserSettings.NONE);
    }

    // Saves a change for the logged-in user; without one, as on the login page, only the cookie keeps the choice
    void change(UnaryOperator<UserSettings> change) {
        username().ifPresent(username -> {
            try {
                userAccountService.changeSettings(username, change);
            } catch (DataAccessException e) {
                LOGGER.error("Could not save the settings of user {}", username, e);
            }
        });
    }

    private static Optional<String> username() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Optional.empty();
        }
        return Optional.of(authentication.getName());
    }
}
