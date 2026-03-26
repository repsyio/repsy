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
package io.repsy.os.server.core.post_precessors;

import io.repsy.libs.protocol.router.ProcessorResult;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolProcessor;
import io.repsy.libs.protocol.router.ProtocolProvider;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class UsagePostProcessor extends ProtocolProcessor {

  private static final int PRIORITY = Integer.MAX_VALUE;

  private final @NonNull UsageUpdateService usageUpdateService;

  public UsagePostProcessor(
      final @NonNull UsageUpdateService usageUpdateService,
      final @NonNull List<ProtocolProvider> protocolProviders) {

    this.usageUpdateService = usageUpdateService;

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

    final var usages = this.getUsages(context);

    if (this.shouldSkip(properties) || !this.hasUsage(usages)) {
      return ProcessorResult.next();
    }

    final var repoInfo = context.<UrlParserProperties>getProperty("urlProperties").getRepoInfo();
    final var usageChangedInfo = new UsageChangedInfo(repoInfo.getStorageKey(), usages);

    log.debug(
        "Processing usage for repo {}: diskUsage={}", repoInfo.getName(), usages.getDiskUsage());

    this.usageUpdateService.updateUsage(usageChangedInfo);

    return ProcessorResult.next();
  }

  private boolean hasUsage(final @Nullable BaseUsages usages) {
    if (usages == null) {
      return false;
    }

    return usages.getDiskUsage() != 0;
  }

  private @Nullable BaseUsages getUsages(final @NonNull ProtocolContext context) {
    final var usagesMap = context.getContextMap();
    return (BaseUsages) usagesMap.get("usages");
  }

  private boolean shouldSkip(final @NonNull Map<@NonNull String, @NonNull Object> properties) {
    return (boolean) properties.getOrDefault("skipUsagePostProcessor", false);
  }
}
