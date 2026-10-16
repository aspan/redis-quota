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

// A change to users or roles that UserAccountService refuses, with the rule it breaks
public class AccountRuleException extends RuntimeException {

    public enum Rule {
        USERNAME_REQUIRED,
        USERNAME_TAKEN,
        EMAIL_TAKEN,
        PASSWORD_TOO_SHORT,
        WRONG_PASSWORD,
        QUOTA_LIMIT_INVALID,
        SENDER_ID_INVALID,
        OWN_ACCOUNT_DELETE,
        OWN_ACCOUNT_DISABLE,
        OWN_ADMIN_ROLE,
        ROLE_NAME_INVALID,
        ROLE_EXISTS,
        BUILT_IN_ROLE_DELETE,
        NOT_FOUND
    }

    private final Rule rule;

    public AccountRuleException(Rule rule) {
        super(rule.name());
        this.rule = rule;
    }

    public Rule rule() {
        return rule;
    }
}
