package io.repsy.protocols.cargo.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;

@NullMarked
public abstract class AbstractCargoDownloadProtocolMethodHandler implements ProtocolMethodHandler {

  private static final Pattern DOWNLOAD_PATTERN =
      Pattern.compile(".*/api/v1/crates/[^/]+/[^/]+/download$");

  public AbstractCargoDownloadProtocolMethodHandler(final CargoProtocolProvider provider) {
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.READ);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {

      if (!HttpMethod.GET.name().equals(request.getMethod())) {
        return Optional.empty();
      }

      if (!DOWNLOAD_PATTERN.matcher(request.getServletPath()).matches()) {
        return Optional.empty();
      }

      return this.getProtocolContext(request);
    };
  }

  protected abstract Optional<ProtocolContext> getProtocolContext(HttpServletRequest request);
}
