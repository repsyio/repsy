package io.repsy.os.server.protocols.cargo.protocol.handlers;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.maven.protocol.CargoProtocolProvider;
import io.repsy.protocols.maven.protocol.handlers.AbstractCargoMeProtocolMethodHandler;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class CargoMeProtocolHandler extends AbstractCargoMeProtocolMethodHandler<UUID> {

  public CargoMeProtocolHandler(final CargoProtocolProvider provider) {
    super(provider);
  }

  @Override
  protected Optional<ProtocolContext> getProtocolContext(final RelativePath relativePath) {
    return Optional.of(ProtocolContextUtils.createWithEmptyRepo("", relativePath));
  }
}
