package io.repsy.protocols.maven.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.maven.protocol.CargoProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractCargoConfigProtocolMethodHandler implements ProtocolMethodHandler {

  public AbstractCargoConfigProtocolMethodHandler(final CargoProtocolProvider provider) {
    provider.registerMethodHandler(this);
  }

  protected abstract Optional<ProtocolContext> getProtocolContext(RelativePath relativePath);

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.READ,
        "skipHeaderPreProcessor", true,
        "skipUsagePostProcessor", true,
        "skipPreProcessor", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      final var method = HttpMethod.valueOf(request.getMethod());
      if (!HttpMethod.GET.equals(method)) {
        return Optional.empty();
      }

      final var path = request.getServletPath();

      if (!path.endsWith("/config.json")) {
        return Optional.empty();
      }

      final var relativePath = new RelativePath("/config.json");
      return this.getProtocolContext(relativePath);
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var path = request.getServletPath();

    final var jsonConfig =
        String.format(
            """
        {
          "dl": "%s/api/v1/crates/{crate}/{version}/download",
          "api": "%s"
        }
        """,
            path, path);

    return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .body(jsonConfig);
  }
}
