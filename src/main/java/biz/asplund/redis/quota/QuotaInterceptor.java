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

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

// Counts each API request against the quota stored for the logged-in user, under their username: the user's
// quota from the users view, or a definition created for the username in the quotas view. Users without one
// are refused. Like QuotaManager, it fails open when Redis cannot be reached
@Component
public class QuotaInterceptor implements HandlerInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(QuotaInterceptor.class);

    // The request attribute holding the QuotaStatus of an allowed request, for handlers that want it
    public static final String STATUS_ATTRIBUTE = "quotaStatus";

    private final QuotaManager quotaManager;
    private final JsonMapper jsonMapper;

    public QuotaInterceptor(QuotaManager quotaManager, JsonMapper jsonMapper) {
        this.quotaManager = quotaManager;
        this.jsonMapper = jsonMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        // Spring Security has authenticated the request with HTTP Basic, so this is only a safeguard
        var principal = request.getUserPrincipal();
        if (principal == null) {
            writeError(response, HttpStatus.UNAUTHORIZED, Map.of("error", "Unauthorized",
                    "message", "Log in with HTTP Basic to use the API"));
            return false;
        }
        var id = principal.getName();

        QuotaStatus status;
        try {
            status = quotaManager.checkAndIncrement(id, 1);
        } catch (IllegalArgumentException e) {
            writeError(response, HttpStatus.FORBIDDEN, Map.of("error", "No Quota",
                    "message", "No quota is set for user " + id));
            return false;
        } catch (RuntimeException e) {
            // QuotaManager fails open on Redis errors itself; this covers anything unexpected
            LOGGER.error("Quota check error for id {}, failing open", id, e);
            return true;
        }

        response.setHeader("X-Quota-Limit", String.valueOf(status.limit()));
        response.setHeader("X-Quota-Used", String.valueOf(status.used()));
        response.setHeader("X-Quota-Remaining", String.valueOf(status.remaining()));
        response.setHeader("X-Quota-Reset", status.resetAt().toString());

        if (!status.allowed()) {
            var body = new LinkedHashMap<String, Object>();
            body.put("error", "Quota Exceeded");
            body.put("message", "You have used your quota of " + status.limit() + " requests");
            body.put("limit", status.limit());
            body.put("used", status.used());
            body.put("remaining", status.remaining());
            body.put("resetAt", status.resetAt().toString());
            writeError(response, HttpStatus.TOO_MANY_REQUESTS, body);
            return false;
        }

        request.setAttribute(STATUS_ATTRIBUTE, status);
        return true;
    }

    private void writeError(HttpServletResponse response, HttpStatus status, Map<String, ?> body) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        jsonMapper.writeValue(response.getWriter(), body);
    }
}
