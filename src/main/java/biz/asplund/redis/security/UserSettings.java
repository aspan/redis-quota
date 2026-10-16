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

/// A user's appearance and language. Each is `null` when not set, so the browser's cookie or language decides.
///
/// @param colorScheme `system`, `light` or `dark`
/// @param locale      a language tag, such as `sv`
public record UserSettings(String colorScheme, String locale) {

    /// Neither appearance nor language set.
    public static final UserSettings NONE = new UserSettings(null, null);

    /// Returns these settings with the color scheme replaced.
    public UserSettings withColorScheme(String colorScheme) {
        return new UserSettings(colorScheme, locale);
    }

    /// Returns these settings with the language replaced.
    public UserSettings withLocale(String locale) {
        return new UserSettings(colorScheme, locale);
    }
}
