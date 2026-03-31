package io.repsy.os.server.protocols.cargo.protocol.shared.crate.repositories;

import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoCrateMeta;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CargoCrateMetaRepository extends JpaRepository<CargoCrateMeta, UUID> {

  Optional<CargoCrateMeta> findByCrateIdAndVersion(UUID crateId, String version);
}
