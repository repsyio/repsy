package io.repsy.os.server.protocols.cargo.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.facade.contract.CargoProtocolFacade;
import io.repsy.protocols.cargo.protocol.handlers.AbstractCargoDownloadProtocolMethodHandler;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class CargoDownloadProtocolMethodHandler
    extends AbstractCargoDownloadProtocolMethodHandler<UUID> {

  public CargoDownloadProtocolMethodHandler(
      @Qualifier("osCargoPathParser") final PathParser basePathParser,
      final CargoProtocolFacade<UUID> facade,
      final CargoProtocolProvider provider) {
    super(basePathParser, facade, provider);
  }
}
