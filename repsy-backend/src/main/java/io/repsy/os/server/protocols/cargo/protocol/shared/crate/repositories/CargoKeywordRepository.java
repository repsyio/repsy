package io.repsy.os.server.protocols.cargo.protocol.shared.crate.repositories;

import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoKeyword;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CargoKeywordRepository extends JpaRepository<CargoKeyword, UUID> {

  Optional<CargoKeyword> findByKeyword(String keyword);
}
