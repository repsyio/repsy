package io.repsy.os.server.protocols.cargo.protocol.shared.crate.repositories;

import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoCrate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CargoCrateRepository extends JpaRepository<CargoCrate, UUID> {

  Optional<CargoCrate> findByRepoIdAndName(UUID repoId, String name);

  Page<CrateListItem> findAllByRepoIdAndNameContaining(UUID repoId, String name, Pageable pageable);
}
