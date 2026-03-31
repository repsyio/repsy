package io.repsy.os.server.protocols.cargo.protocol.facades;

import io.repsy.protocols.cargo.protocol.facade.AbstractCargoProtocolFacade;
import io.repsy.protocols.cargo.shared.crate.services.CargoCrateService;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@NullMarked
@Component
public class CargoProtocolFacade extends AbstractCargoProtocolFacade<UUID> {

  public CargoProtocolFacade(
      final CargoStorageService cargoStorageService,
      final CargoCrateService<UUID> cargoCrateService,
      final ObjectMapper objectMapper) {

    super(cargoStorageService, cargoCrateService, objectMapper);
  }
}
