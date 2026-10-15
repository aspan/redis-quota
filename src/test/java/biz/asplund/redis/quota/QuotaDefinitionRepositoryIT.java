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

import biz.asplund.testcontainers.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@DataRedisTest
class QuotaDefinitionRepositoryIT {

	@Autowired
	private QuotaDefinitionRepository repository;

	@Test
	void savesAndFindsDefinitionById() {
		var definition = new QuotaDefinition(uniqueId(), QuotaPeriod.MONTHLY, 10_000);

		repository.save(definition);

		assertThat(repository.findById(definition.id())).contains(definition);
	}

	@Test
	void saveReplacesExistingDefinition() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 100));

		var updated = new QuotaDefinition(id, QuotaPeriod.WEEKLY, 500);
		repository.save(updated);

		assertThat(repository.findById(id)).contains(updated);
	}

	@Test
	void deleteRemovesDefinition() {
		var id = uniqueId();
		repository.save(new QuotaDefinition(id, QuotaPeriod.DAILY, 100));

		repository.deleteById(id);

		assertThat(repository.existsById(id)).isFalse();
		assertThat(repository.findById(id)).isEmpty();
	}

	@Test
	void findByUnknownIdIsEmpty() {
		assertThat(repository.findById(uniqueId())).isEmpty();
	}

	private static String uniqueId() {
		return "test-id-" + UUID.randomUUID();
	}

}
