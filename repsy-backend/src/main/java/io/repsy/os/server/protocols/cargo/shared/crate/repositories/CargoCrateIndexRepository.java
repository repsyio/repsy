/*
 * Copyright 2026 the original author or authors.
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
package io.repsy.os.server.protocols.cargo.shared.crate.repositories;

import io.repsy.os.server.protocols.cargo.shared.crate.entities.CargoCrateIndex;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
@NullMarked
public interface CargoCrateIndexRepository extends JpaRepository<CargoCrateIndex, UUID> {

  List<CargoCrateIndex> findAllByCrateRepoIdAndName(UUID repoId, String name);

  List<CargoCrateIndex> findAllByCrateId(UUID crateId);

  Optional<CargoCrateIndex> findByCrateIdAndVers(UUID crateId, String vers);
}
