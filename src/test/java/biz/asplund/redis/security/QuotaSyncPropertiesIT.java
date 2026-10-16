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

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

// Binds quota.sync.* the way Spring Boot does, without starting the application
class QuotaSyncPropertiesIT {

    @Test
    void runsDailyAtHalfPastThreeByDefault() {
        var properties = bind(Map.of());

        assertThat(properties.cron()).isEqualTo("0 30 3 * * *");
        assertThat(properties.daily()).isTrue();
    }

    @Test
    void bindsCustomSchedule() {
        assertThat(bind(Map.of("quota.sync.cron", "0 0 */6 * * *")).cron()).isEqualTo("0 0 */6 * * *");
    }

    @Test
    void turnsDailyRunOffWithDash() {
        assertThat(bind(Map.of("quota.sync.cron", "-")).daily()).isFalse();
    }

    @Test
    void rejectsInvalidCronExpression() {
        assertThatExceptionOfType(BindException.class)
                .isThrownBy(() -> bind(Map.of("quota.sync.cron", "every night")))
                .havingRootCause()
                .withMessageContaining("quota.sync.cron must be a cron expression or -");
    }

    private static QuotaSyncProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("quota.sync", QuotaSyncProperties.class);
    }
}
