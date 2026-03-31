package io.repsy.os.server.protocols.cargo.protocol.shared.crate.repositories;

import io.repsy.os.server.protocols.cargo.protocol.shared.crate.entities.CargoCrate;
import io.repsy.protocols.cargo.shared.crate.dtos.CrateListItem;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CargoCrateRepository extends JpaRepository<CargoCrate, UUID> {

  Optional<CargoCrate> findByRepoIdAndName(UUID repoId, String name);

  Page<CrateListItem> findAllByRepoIdAndNameContaining(UUID repoId, String name, Pageable pageable);

  List<CargoCrate> findAllByRepoId(UUID repoId);
}
