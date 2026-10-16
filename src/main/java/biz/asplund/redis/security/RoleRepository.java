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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

/// The roles granted to users, stored in the `roles` table.
public interface RoleRepository extends JpaRepository<Role, Long> {

    /// Finds the role with exactly this name.
    Optional<Role> findByName(String name);

    /// Returns pairs of role id and the number of users holding it; roles no user holds are left out.
    @Query("select r.id, count(u) from UserAccount u join u.roles r group by r.id")
    List<Object[]> countUsersByRole();
}
