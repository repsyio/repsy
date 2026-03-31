package io.repsy.protocols.cargo.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.dtos.CargoErrorResponse;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
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
      if (!HttpMethod.GET.name().equals(request.getMethod())) {
        return Optional.empty();
      }
      if (!request.getServletPath().endsWith("/config.json")) {
        return Optional.empty();
      }
      return this.getProtocolContext(new RelativePath("/config.json"));
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var path = request.getServletPath();
      final var baseUrl = path.substring(0, path.lastIndexOf("/config.json"));

      final var jsonConfig = String.format("""
          {
            "dl": "%s/api/v1/crates/{crate}/{version}/download",
            "api": "%s"
          }
          """, baseUrl, baseUrl);

      return ResponseEntity.ok()
        .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
        .body(jsonConfig);

    } catch (final Exception e) {
      return buildCargoErrorResponse(HttpStatus.INTERNAL_SERVER_ERROR, e.getMessage());
    }
  }

  protected ResponseEntity<Object> buildCargoErrorResponse(
    final HttpStatus status, final String detail) {
    return ResponseEntity.status(status)
      .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
      .body(CargoErrorResponse.of(detail));
  }

  protected ResponseEntity<Object> buildNotFoundErrorResponse(final String resourceName) {
    return buildCargoErrorResponse(HttpStatus.NOT_FOUND, resourceName + " not found");
  }
}
