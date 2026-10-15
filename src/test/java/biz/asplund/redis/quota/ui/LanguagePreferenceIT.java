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

import biz.asplund.testcontainers.TestcontainersConfiguration;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockCookie;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.LocaleResolver;

import java.time.Duration;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
class LanguagePreferenceIT {

	private static final Locale SWEDISH = Locale.of("sv");

	@Autowired
	private LocaleResolver localeResolver;

	@Autowired
	private LanguagePreference languagePreference;

	@Test
	void usesBrowserLanguageWithoutCookie() {
		assertThat(languagePreference.resolve(request("sv-SE,sv;q=0.9,en;q=0.8"))).isEqualTo(SWEDISH);
		assertThat(languagePreference.resolve(request("en-GB,en;q=0.9"))).isEqualTo(Locale.ENGLISH);
	}

	@Test
	void picksBestTranslatedBrowserLanguage() {
		assertThat(languagePreference.resolve(request("de-DE,de;q=0.9,sv;q=0.5"))).isEqualTo(SWEDISH);
	}

	@Test
	void fallsBackToEnglish() {
		assertThat(languagePreference.resolve(request("de-DE,fr;q=0.5"))).isEqualTo(Locale.ENGLISH);
		assertThat(languagePreference.resolve(request(null))).isEqualTo(Locale.ENGLISH);
		assertThat(languagePreference.resolve(request("not a header;;"))).isEqualTo(Locale.ENGLISH);
	}

	@Test
	void remembersChoiceInCookie() {
		var response = new MockHttpServletResponse();
		localeResolver.setLocale(request("en"), response, SWEDISH);

		var cookie = (MockCookie) response.getCookie(I18nConfiguration.LOCALE_COOKIE);
		assertThat(cookie).isNotNull();
		assertThat(cookie.getValue()).isEqualTo("sv");
		assertThat(cookie.getMaxAge()).isEqualTo(Duration.ofDays(365).toSeconds());
		assertThat(cookie.getSameSite()).isEqualTo("Lax");
		assertThat(cookie.isHttpOnly()).isTrue();

		var next = request("en-US,en;q=0.9");
		next.setCookies(new Cookie(I18nConfiguration.LOCALE_COOKIE, "sv"));
		assertThat(languagePreference.resolve(next)).isEqualTo(SWEDISH);
	}

	@Test
	void ignoresCookieForUntranslatedLanguage() {
		var request = request("sv");
		request.setCookies(new Cookie(I18nConfiguration.LOCALE_COOKIE, "de"));

		assertThat(languagePreference.resolve(request)).isEqualTo(Locale.ENGLISH);
	}

	private static MockHttpServletRequest request(String acceptLanguage) {
		var request = new MockHttpServletRequest();
		if (acceptLanguage != null) {
			request.addHeader("Accept-Language", acceptLanguage);
		}
		return request;
	}
}
