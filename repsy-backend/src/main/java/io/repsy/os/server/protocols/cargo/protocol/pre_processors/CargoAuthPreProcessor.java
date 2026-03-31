package io.repsy.os.server.protocols.cargo.protocol.pre_processors;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.os.server.protocols.cargo.protocol.shared.auth.services.CargoAuthComponent;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.protocols.cargo.protocol.CargoProtocolProvider;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@NullMarked
public class CargoAuthPreProcessor extends ProtocolProcessor {

  private static final int PRIORITY = 100;
  private static final String SKIP_PRE_PROCESSOR_KEY = "skipPreProcessor";
  private static final String PERMISSION_KEY = "permission";

  private final CargoAuthComponent authComponent;
  private final CargoProtocolProvider provider;

  @PostConstruct
  public void register() {

    this.provider.registerPreProcessor(this);
  }

  @Override
  protected int getPriority() {

    return PRIORITY;
  }

  @Override
  protected ProcessorResult process(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Map<String, Object> properties) {

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);

    if (this.shouldSkipAuthentication(repoInfo, properties)) {
      return ProcessorResult.next();
    }

    final var authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);

    if (authHeader == null && repoInfo.isPrivateRepo()) {
      throw new UnAuthorizedException(ErrorConstants.UN_AUTHORIZED);
    }

    final var permission = (Permission) properties.get(PERMISSION_KEY);

    // this.authComponent.authorizeRequest(repoInfo, authHeader, permission);

    return ProcessorResult.next();
  }

  private boolean shouldSkipAuthentication(
      final RepoInfo repoInfo, final Map<String, Object> properties) {

    final var skipPreProcessor = (boolean) properties.getOrDefault(SKIP_PRE_PROCESSOR_KEY, false);

    if (skipPreProcessor) {
      return true;
    }

    final var permission = (Permission) properties.get(PERMISSION_KEY);

    if (this.isWritePermissionRequired(permission)) {
      return false;
    }

    return !repoInfo.isPrivateRepo();
  }

  private boolean isWritePermissionRequired(final Permission permission) {

    return permission == Permission.MANAGE || permission == Permission.WRITE;
  }
}
