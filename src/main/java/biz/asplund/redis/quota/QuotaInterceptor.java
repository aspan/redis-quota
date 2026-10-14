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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public class QuotaInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuotaInterceptor.class);

    @Autowired
    private QuotaManager quotaManager;

    private QuotaConfig config;
    private KeyExtractor keyExtractor;

    public QuotaInterceptor() {}

    public QuotaInterceptor(QuotaConfig config, KeyExtractor keyExtractor) {
        this.config = config;
        this.keyExtractor = keyExtractor;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        var userId = keyExtractor.extractKey(request);

        try {
            var status = quotaManager.checkAndIncrement(userId, config, 1);

            // Set response headers
            response.setHeader("X-Quota-Limit", String.valueOf(status.getLimit()));
            response.setHeader("X-Quota-Used", String.valueOf(status.getUsed()));
            response.setHeader("X-Quota-Remaining", String.valueOf(status.getRemaining()));
            response.setHeader("X-Quota-Reset", status.getResetAt().toString());

            if (!status.isAllowed()) {
                response.setStatus(429);
                response.setContentType("application/json");
                response.getWriter().write(
                    "{\n" +
                    "  \"error\": \"Quota Exceeded\",\n" +
                    "  \"message\": \"You have exceeded your " + config.period() + " quota\",\n" +
                    "  \"limit\": " + status.getLimit() + ",\n" +
                    "  \"used\": " + status.getUsed() + ",\n" +
                    "  \"resetAt\": \"" + status.getResetAt().toString() + "\"\n" +
                    "}"
                );
                return false;
            }

            // Store quota status in request for later use
            request.setAttribute("quotaStatus", status);
            return true;
        } catch (Exception e) {
            // Fail open on error
            LOGGER.error("Quota check error for user {}, failing open", userId, e);
            return true;
        }
    }

    public interface KeyExtractor {
        String extractKey(HttpServletRequest request);
    }
}