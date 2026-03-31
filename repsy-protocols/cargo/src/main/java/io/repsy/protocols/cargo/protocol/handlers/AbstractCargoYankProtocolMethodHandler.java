package io.repsy.protocols.cargo.protocol.handlers;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.cargo.protocol.dtos.CargoErrorResponse;
import io.repsy.protocols.cargo.protocol.facade.contract.CargoProtocolFacade;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

@NullMarked
public abstract class AbstractCargoYankProtocolMethodHandler<ID> implements ProtocolMethodHandler {

  private static final Pattern YANK_PATTERN =
      Pattern.compile(".*/api/v1/crates/[^/]+/[^/]+/(yank|unyank)$");

  private final PathParser basePathParser;
  private final CargoProtocolFacade<ID> facade;

  public AbstractCargoYankProtocolMethodHandler(
      final PathParser basePathParser,
      final CargoProtocolFacade<ID> facade,
      final CargoProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.facade = facade;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.DELETE, HttpMethod.PUT);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      final var method = HttpMethod.valueOf(request.getMethod());
      if (!this.getSupportedMethods().contains(method)) {
        return Optional.empty();
      }

      final var parsedPathOpt = this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!YANK_PATTERN.matcher(relativePath).matches()) {
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    try {
      final var relativePath = ProtocolContextUtils.getRelativePath(context).getPath();
      final var isYank = relativePath.endsWith("/yank");

      if (isYank) {
        this.facade.yank(context);
      } else {
        this.facade.unyank(context);
      }

      return ResponseEntity.ok()
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(Map.of("ok", true));
    } catch (final Exception e) {
      return ResponseEntity.status(HttpStatus.BAD_REQUEST)
          .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
          .body(CargoErrorResponse.of(e.getMessage()));
    }
  }
}
