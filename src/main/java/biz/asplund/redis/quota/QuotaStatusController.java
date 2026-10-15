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

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.Map;

// Shows API clients their own quota. QuotaConfiguration leaves this path out of the QuotaInterceptor, so asking
// does not use up quota and still works when the quota is used up
@RestController
public class QuotaStatusController {

    public static final String PATH = "/api/quota";

    private final QuotaDefinitionRepository quotaDefinitionRepository;
    private final QuotaManager quotaManager;

    public QuotaStatusController(QuotaDefinitionRepository quotaDefinitionRepository, QuotaManager quotaManager) {
        this.quotaDefinitionRepository = quotaDefinitionRepository;
        this.quotaManager = quotaManager;
    }

    // lastUsed is null until the first allowed request
    public record QuotaStatusResponse(String id, QuotaPeriod period, int limit, int used, int remaining,
            Instant resetAt, Instant lastUsed) {
    }

    @GetMapping(PATH)
    public ResponseEntity<?> status(Principal principal) {
        var id = principal.getName();
        QuotaDefinition definition;
        try {
            definition = quotaDefinitionRepository.findById(id).orElse(null);
        } catch (DataAccessException e) {
            // Unlike requests, which fail open, a status read from nowhere would only mislead
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", "Quota Unavailable",
                    "message", "The quota cannot be read right now"));
        }
        if (definition == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("error", "No Quota",
                    "message", "No quota is set for user " + id));
        }
        var status = quotaManager.getStatus(id, definition.config());
        return ResponseEntity.ok(new QuotaStatusResponse(id, definition.period(), status.limit(), status.used(),
                status.remaining(), status.resetAt(), quotaManager.getLastUsed(id).orElse(null)));
    }
}
