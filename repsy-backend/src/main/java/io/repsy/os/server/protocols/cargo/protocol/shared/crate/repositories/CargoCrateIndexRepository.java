package io.repsy.os.server.protocols.cargo.protocol.shared.crate.repositories;

import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoCrateIndex;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CargoCrateIndexRepository extends JpaRepository<CargoCrateIndex, UUID> {

  List<CargoCrateIndex> findAllByCrateRepoIdAndName(UUID repoId, String name);

  List<CargoCrateIndex> findAllByCrateId(UUID crateId);

  Optional<CargoCrateIndex> findByCrateIdAndVers(UUID crateId, String vers);
}
