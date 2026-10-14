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

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

// Binds quota.ui.i18n.* the way Spring Boot does, without starting the application
class I18nPropertiesIT {

	private static final Locale SWEDISH = Locale.of("sv");

	@Test
	void defaultsToEnglishAndSwedish() {
		var properties = bind(Map.of());

		assertThat(properties.locales()).containsExactly(Locale.ENGLISH, SWEDISH);
		assertThat(properties.fallback()).isEqualTo(Locale.ENGLISH);
	}

	@Test
	void usesFirstConfiguredLocaleAsFallback() {
		var properties = bind(Map.of("quota.ui.i18n.locales", "sv,en"));

		assertThat(properties.locales()).containsExactly(SWEDISH, Locale.ENGLISH);
		assertThat(properties.supported(Locale.GERMAN)).isEqualTo(SWEDISH);
		assertThat(properties.supported(Locale.UK)).isEqualTo(Locale.ENGLISH);
		assertThat(properties.fromAcceptLanguage(request("de-DE,fr;q=0.5"))).isEqualTo(SWEDISH);
	}

	@Test
	void limitsLanguagesToConfiguredOnes() {
		var properties = bind(Map.of("quota.ui.i18n.locales", "en"));

		assertThat(properties.supported(SWEDISH)).isEqualTo(Locale.ENGLISH);
		assertThat(properties.fromAcceptLanguage(request("sv-SE,sv;q=0.9"))).isEqualTo(Locale.ENGLISH);
	}

	@Test
	void rejectsEmptyList() {
		assertThatExceptionOfType(BindException.class)
				.isThrownBy(() -> bind(Map.of("quota.ui.i18n.locales", "")))
				.havingRootCause().withMessageContaining("at least one locale");
	}

	private static I18nProperties bind(Map<String, String> source) {
		return new Binder(new MapConfigurationPropertySource(source)).bindOrCreate("quota.ui.i18n", I18nProperties.class);
	}

	private static MockHttpServletRequest request(String acceptLanguage) {
		var request = new MockHttpServletRequest();
		request.addHeader("Accept-Language", acceptLanguage);
		return request;
	}
}
