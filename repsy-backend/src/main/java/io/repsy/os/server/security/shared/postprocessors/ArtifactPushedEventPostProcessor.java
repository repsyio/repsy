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
import io.repsy.libs.scanner.VulnerabilityScannerRegistry;
import io.repsy.os.server.shared.utils.UrlPropertiesUtils;
import io.repsy.protocols.shared.handlers.HandlerPropertyKeys;
import io.repsy.protocols.shared.utils.BlobDigests;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

@Component
public class ArtifactPushedEventPostProcessor extends ProtocolProcessor {

  // Before UsagePostProcessor (Integer.MAX_VALUE - 1) and H2CheckpointPostProcessor (Integer.
  // MAX_VALUE, RPS-1556), which must run after every write -- including usage tracking's own -- has
  // committed.
  private static final int PRIORITY = Integer.MAX_VALUE - 2;
  private static final String ARTIFACT_NAME = "artifactName";
  private static final String ARTIFACT_VERSION = "artifactVersion";
  private static final String STORAGE_PATH = "storagePath";

  private final ApplicationEventPublisher eventPublisher;
  private final VulnerabilityScannerRegistry scannerRegistry;

  public ArtifactPushedEventPostProcessor(
      final ApplicationEventPublisher eventPublisher,
      final VulnerabilityScannerRegistry scannerRegistry,
      final List<ProtocolProvider> protocolProviders) {

    this.eventPublisher = eventPublisher;
    this.scannerRegistry = scannerRegistry;

    for (final var protocolProvider : protocolProviders) {
      protocolProvider.registerPostProcessor(this);
    }
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

    if (!this.isWriteOperation(properties)) {
      return ProcessorResult.next();
    }

    final var repoInfo = UrlPropertiesUtils.getRepoInfo(context);

    if (!repoInfo.isSecurityScanEnabled()) {
      return ProcessorResult.next();
    }

    if (this.scannerRegistry.findScanner(repoInfo.getType().name()).isEmpty()) {
      return ProcessorResult.next();
    }

    final var artifactName = context.<String>getProperty(ARTIFACT_NAME);
    final var artifactVersion = context.<String>getProperty(ARTIFACT_VERSION);

    if (!isPushedCoordinate(artifactName, artifactVersion)) {
      return ProcessorResult.next();
    }

    final var relativePath = UrlPropertiesUtils.getRelativePath(context);
    final var storagePathOverride = context.<String>getProperty(STORAGE_PATH);
    final var storagePath =
        storagePathOverride != null ? storagePathOverride : relativePath.getPath();

    this.eventPublisher.publishEvent(
        new ArtifactPushedEvent(
            repoInfo.getStorageKey(),
            repoInfo.getType().name(),
            repoInfo.getName(),
            storagePath,
            artifactName,
            artifactVersion,
            true,
            false));

    return ProcessorResult.next();
  }

  private boolean isWriteOperation(final Map<String, Object> properties) {
    return (boolean) properties.getOrDefault(HandlerPropertyKeys.WRITE_OPERATION, false);
  }

  /**
   * Only a request that resolved an artifact coordinate pushed an artifact: the upload start, chunk
   * and status routes of an OCI registry are write operations too (so they authenticate) but
   * publish no coordinate, and {@code ArtifactScanListener} would drop such an event anyway
   * (RPS-2161). A digest reference is not a pushed version either.
   */
  private static boolean isPushedCoordinate(
      final @Nullable String artifactName, final @Nullable String artifactVersion) {
    return artifactName != null && artifactVersion != null && !isDigestReference(artifactVersion);
  }

  private static boolean isDigestReference(final @Nullable String artifactVersion) {
    return artifactVersion != null && BlobDigests.startsWithDigestPrefix(artifactVersion);
  }
}
