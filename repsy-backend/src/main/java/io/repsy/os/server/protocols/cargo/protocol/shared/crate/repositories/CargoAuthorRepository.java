package io.repsy.os.server.protocols.cargo.protocol.shared.crate.repositories;

import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoAuthor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CargoAuthorRepository extends JpaRepository<CargoAuthor, UUID> {

  Optional<CargoAuthor> findByAuthor(String author);
}
