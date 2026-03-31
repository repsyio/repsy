package io.repsy.os.server.protocols.cargo.protocol.handlers;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.protocols.cargo.protocol.shared.auth.services.CargoAuthComponent;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.handlers.AbstractCargoMeProtocolMethodHandler;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class CargoMeProtocolHandler extends AbstractCargoMeProtocolMethodHandler {

  public CargoMeProtocolHandler(
      final CargoAuthComponent authComponent, final CargoProtocolProvider provider) {
    super(authComponent::authenticateAndCreateToken, provider);
  }

  @Override
  protected Optional<ProtocolContext> getProtocolContext(final RelativePath relativePath) {
    return Optional.of(ProtocolContextUtils.createWithEmptyRepo("", relativePath));
  }
}
