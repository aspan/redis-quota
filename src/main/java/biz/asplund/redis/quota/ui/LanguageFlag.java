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

import com.vaadin.flow.component.dependency.NpmPackage;
import com.vaadin.flow.component.html.Image;

import java.util.Locale;
import java.util.Map;

// The flag of a language's country, from the flag-icons npm package. Only the package's 4x3 SVGs are copied to the
// static assets, so a page loads the flags it shows rather than the package's stylesheet with all of them. The flag
// is decorative: the language's name is always shown beside it, so the image has an empty alt text
@NpmPackage(value = "flag-icons", version = "7.5.0", assets = "flags/4x3/*.svg:flag-icons/4x3")
class LanguageFlag extends Image {

    // Where the copied assets are served, open before login too; relative, so it follows the application's path
    static final String PATH = "VAADIN/static/assets/flag-icons/4x3/";

    // Languages whose code is not the code of the country whose flag stands for them; others use the locale's
    // country, or else the language code, which is the country code for many languages, such as de, fi and fr
    private static final Map<String, String> COUNTRIES = Map.ofEntries(
            Map.entry("en", "gb"), Map.entry("sv", "se"), Map.entry("da", "dk"), Map.entry("nb", "no"),
            Map.entry("nn", "no"), Map.entry("ja", "jp"), Map.entry("zh", "cn"), Map.entry("ko", "kr"),
            Map.entry("el", "gr"), Map.entry("cs", "cz"), Map.entry("uk", "ua"), Map.entry("he", "il"),
            Map.entry("et", "ee"), Map.entry("sl", "si"), Map.entry("fa", "ir"), Map.entry("vi", "vn"),
            Map.entry("ga", "ie"), Map.entry("ka", "ge"), Map.entry("hy", "am"), Map.entry("kk", "kz"),
            Map.entry("ar", "sa"), Map.entry("hi", "in"), Map.entry("sq", "al"), Map.entry("ms", "my"));

    LanguageFlag(Locale locale) {
        super(PATH + country(locale) + ".svg", "");
        // The SVGs are 4:3
        setHeight("0.9em");
        setWidth("1.2em");
        getStyle().set("flex", "none").set("border-radius", "2px").set("vertical-align", "middle");
    }

    static String country(Locale locale) {
        if (!locale.getCountry().isEmpty()) {
            return locale.getCountry().toLowerCase(Locale.ROOT);
        }
        return COUNTRIES.getOrDefault(locale.getLanguage(), locale.getLanguage());
    }
}
