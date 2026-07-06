/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.os.server.security.shared.postprocessors;

import io.repsy.core.events.ArtifactPushedEvent;
import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.os.server.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.NonNull;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * Priority is intentionally {@code Integer.MAX_VALUE - 1}: {@code ProtocolProcessor} equality is
 * defined purely by priority, and post-processors are stored in a {@code TreeSet}, so reusing the
 * same priority as another registered post-processor (e.g. {@code UsagePostProcessor}, which uses
 * {@code Integer.MAX_VALUE}) would silently fail to register.
 */
@Component
public class ArtifactPushedEventPostProcessor extends ProtocolProcessor {

  private static final int PRIORITY = Integer.MAX_VALUE - 1;
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";
  private static final String WRITE_OPERATION = "writeOperation";

  private final @NonNull ApplicationEventPublisher eventPublisher;

  public ArtifactPushedEventPostProcessor(
      final @NonNull ApplicationEventPublisher eventPublisher,
      final @NonNull List<ProtocolProvider> protocolProviders) {

    this.eventPublisher = eventPublisher;

    for (final var protocolProvider : protocolProviders) {
      protocolProvider.registerPostProcessor(this);
    }
  }

  @Override
  protected int getPriority() {
    return PRIORITY;
  }

  @Override
  protected @NonNull ProcessorResult process(
      final @NonNull ProtocolContext context,
      final @NonNull HttpServletRequest request,
      final @NonNull HttpServletResponse response,
      final @NonNull Map<@NonNull String, @NonNull Object> properties) {

    if (!this.isWriteOperation(properties)) {
      return ProcessorResult.next();
    }

    final var repoInfo = ProtocolContextUtils.getRepoInfo(context);
    final var relativePath = ProtocolContextUtils.getRelativePath(context);

    this.eventPublisher.publishEvent(
        new ArtifactPushedEvent(
            repoInfo.getStorageKey(),
            repoInfo.getType().name(),
            repoInfo.getName(),
            relativePath.getPath(),
            context.<String>getProperty(ARTIFACT_NAME),
            context.<String>getProperty(ARTIFACT_VERSION)));

    return ProcessorResult.next();
  }

  private boolean isWriteOperation(final @NonNull Map<@NonNull String, @NonNull Object> properties) {
    return (boolean) properties.getOrDefault(WRITE_OPERATION, false);
  }
}
