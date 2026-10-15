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

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

// Which country's flag stands for a language, without starting the application
class LanguageFlagIT {

	@ParameterizedTest
	@CsvSource({"en, gb", "sv, se", "da, dk", "nb, no", "ja, jp", "de, de", "fi, fi", "fr, fr", "en-US, us",
			"pt-BR, br"})
	void picksFlagCountry(String languageTag, String country) {
		var locale = Locale.forLanguageTag(languageTag);

		assertThat(LanguageFlag.country(locale)).isEqualTo(country);
		assertThat(new LanguageFlag(locale).getSrc()).isEqualTo(LanguageFlag.PATH + country + ".svg");
	}
}
