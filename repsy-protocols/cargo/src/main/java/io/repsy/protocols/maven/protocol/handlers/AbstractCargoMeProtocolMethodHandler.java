package io.repsy.protocols.maven.protocol.handlers;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;

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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractCargoMeProtocolMethodHandler<ID> implements ProtocolMethodHandler {

  // private final CargoAuthService<ID> authService;

  public AbstractCargoMeProtocolMethodHandler(
      // final CargoAuthService<ID> authService,
      final CargoProtocolProvider provider) {
    // this.authService = authService;

    provider.registerMethodHandler(this);
  }

  protected abstract Optional<ProtocolContext> getProtocolContext(RelativePath relativePath);

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET, HttpMethod.HEAD);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of(
        "permission", Permission.NONE,
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
      if (!"/me".equals(path)) {
        return Optional.empty();
      }

      final var relativePath = new RelativePath("/me");

      return this.getProtocolContext(relativePath);
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    try {
      final var path = request.getServletPath();
      final var authHeader = request.getHeader(AUTHORIZATION);

      if (authHeader == null) {
        return this.buildUnauthorizedResponse();
      }

      final var cargoApiToken = "test_token";

      return ResponseEntity.ok(Map.of("token", cargoApiToken));

    } catch (final Exception _) {
      return this.buildUnauthorizedResponse();
    }
  }

  private ResponseEntity<Object> buildUnauthorizedResponse() {
    return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
  }
}
