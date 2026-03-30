package io.repsy.os.server.protocols.cargo.protocol.handlers;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.maven.protocol.CargoProtocolProvider;
import io.repsy.protocols.maven.protocol.handlers.AbstractCargoConfigProtocolMethodHandler;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class CargoConfigProtocolHandler extends AbstractCargoConfigProtocolMethodHandler {

  public CargoConfigProtocolHandler(final CargoProtocolProvider provider) {
    super(provider);
  }

  @Override
  protected Optional<ProtocolContext> getProtocolContext(final RelativePath relativePath) {
    return Optional.of(ProtocolContextUtils.createWithEmptyRepo("", relativePath));
  }
}
