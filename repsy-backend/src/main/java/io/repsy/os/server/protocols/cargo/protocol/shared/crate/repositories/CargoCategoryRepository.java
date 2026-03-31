package io.repsy.os.server.protocols.cargo.protocol.shared.crate.repositories;

import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoCategory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CargoCategoryRepository extends JpaRepository<CargoCategory, UUID> {

  Optional<CargoCategory> findByCategory(String category);
}
