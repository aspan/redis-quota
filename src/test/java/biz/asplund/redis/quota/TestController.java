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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;

@RestController
public class TestController {

    @Autowired
    private QuotaManager quotaManager;

    @GetMapping("/api/test")
    public String testQuota(HttpServletRequest request) {
        // Get quota status from request attribute set by interceptor
        var status = (QuotaStatus) request.getAttribute("quotaStatus");
        if (status != null) {
            return "Quota Status: " + status.used() + "/" + status.limit() +
                   ", Remaining: " + status.remaining();
        }
        return "No quota information available";
    }

    @GetMapping("/api/usage")
    public String getUsage(@RequestHeader(value = "X-API-Key", required = false) String apiKey) {
        var id = apiKey != null ? apiKey : "anonymous";
        var usage = quotaManager.getUsage(id, QuotaPeriod.MONTHLY);
        return "Current monthly usage for " + id + ": " + usage;
    }
}