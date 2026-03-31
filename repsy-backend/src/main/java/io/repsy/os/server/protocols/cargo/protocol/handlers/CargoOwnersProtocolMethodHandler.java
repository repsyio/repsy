package io.repsy.os.server.protocols.cargo.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.handlers.AbstractCargoOwnersProtocolMethodHandler;
import org.jspecify.annotations.NullMarked;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class CargoOwnersProtocolMethodHandler extends AbstractCargoOwnersProtocolMethodHandler {

  public CargoOwnersProtocolMethodHandler(
      @Qualifier("osCargoPathParser") final PathParser basePathParser,
      final CargoProtocolProvider provider) {
    super(basePathParser, provider);
  }
}
