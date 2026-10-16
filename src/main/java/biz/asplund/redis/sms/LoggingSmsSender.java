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
package biz.asplund.redis.sms;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

// Sends nothing, only logs the message; replace it with an SmsSender for a real gateway
@Component
public class LoggingSmsSender implements SmsSender {

    private static final Logger LOGGER = LoggerFactory.getLogger(LoggingSmsSender.class);

    @Override
    public void send(String recipient, String text) {
        LOGGER.info("SMS to {} ({} characters): {}", recipient, text.length(), text);
    }
}
