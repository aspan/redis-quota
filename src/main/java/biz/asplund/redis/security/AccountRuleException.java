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

/// A change to users or roles that [UserAccountService] refuses, with the rule it breaks.
public class AccountRuleException extends RuntimeException {

    /// The rules [UserAccountService] enforces.
    public enum Rule {
        /// The username is `null` or blank.
        USERNAME_REQUIRED,
        /// Another user has the username, or has it as their email address.
        USERNAME_TAKEN,
        /// Another user has the email address, whatever its case, or has it as their username.
        EMAIL_TAKEN,
        /// The password is `null` or shorter than [UserAccountService#MIN_PASSWORD_LENGTH].
        PASSWORD_TOO_SHORT,
        /// The current password, given to confirm a change of password, is wrong.
        WRONG_PASSWORD,
        /// The quota has no period or a negative limit.
        QUOTA_LIMIT_INVALID,
        /// A sender id is neither a phone number nor an alphanumeric id.
        SENDER_ID_INVALID,
        /// The acting user tried to delete their own account.
        OWN_ACCOUNT_DELETE,
        /// The acting user tried to disable their own account.
        OWN_ACCOUNT_DISABLE,
        /// The acting user tried to take the `ADMIN` role from their own account.
        OWN_ADMIN_ROLE,
        /// The role name is not upper case letters, digits and underscores starting with a letter, at most 50
        /// long, or it starts with `ROLE_`.
        ROLE_NAME_INVALID,
        /// A role with the name exists.
        ROLE_EXISTS,
        /// The role is one of [UserAccountService#BUILT_IN_ROLES], which cannot be deleted.
        BUILT_IN_ROLE_DELETE,
        /// No user or role has the id or username, or a role name names no role.
        NOT_FOUND
    }

    private final Rule rule;

    /// Creates the exception for the rule, with its name as the message.
    public AccountRuleException(Rule rule) {
        super(rule.name());
        this.rule = rule;
    }

    /// Returns the rule the change breaks.
    public Rule rule() {
        return rule;
    }
}
