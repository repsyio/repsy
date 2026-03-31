package io.repsy.os.server.protocols.cargo.protocol.shared.crate.repositories;

import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoCategory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CargoCategoryRepository extends JpaRepository<CargoCategory, UUID> {

  Optional<CargoCategory> findByCategory(String category);
}
