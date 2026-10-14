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

package biz.asplund.redis.quota;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the application against a Redis port with nothing listening on it.
 */
@SpringBootTest(properties = "spring.data.redis.connect-timeout=500ms")
class QuotaManagerFailOpenIT {

	private static final Instant NOW = Instant.parse("2026-10-14T08:15:30Z");

	@Autowired
	private QuotaManager quotaManager;

	@TestBean
	private Clock clock;

	static Clock clock() {
		return Clock.fixed(NOW, ZoneOffset.UTC);
	}

	@DynamicPropertySource
	static void unreachableRedis(DynamicPropertyRegistry registry) {
		registry.add("spring.data.redis.host", () -> "localhost");
		registry.add("spring.data.redis.port", QuotaManagerFailOpenIT::unusedPort);
	}

	@Test
	void checkAndIncrementAllowsRequestWhenRedisIsDown() {
		var config = new QuotaConfig(100, QuotaPeriod.DAILY);

		var status = quotaManager.checkAndIncrement("user", config, 10);

		assertThat(status).isEqualTo(new QuotaStatus(true, 100, 0, 100, Instant.parse("2026-10-15T00:00:00Z")));
	}

	@Test
	void getUsageReturnsZeroWhenRedisIsDown() {
		assertThat(quotaManager.getUsage("user", QuotaPeriod.DAILY)).isZero();
	}

	private static int unusedPort() {
		try (var socket = new ServerSocket(0)) {
			return socket.getLocalPort();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

}
