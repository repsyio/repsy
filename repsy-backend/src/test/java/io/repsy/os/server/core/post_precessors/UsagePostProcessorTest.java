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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import io.repsy.libs.storage.core.dtos.RelativePath;
import io.repsy.os.server.core.UrlParserProperties;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.usage.dtos.UsageChangedInfo;
import io.repsy.os.shared.usage.services.UsageUpdateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * RPS-1114: {@link UsagePostProcessor} must opt in to running on a failed request (bytes a failing
 * handler already wrote still need to be settled), while leaving the response the client sees
 * untouched — {@link UsagePostProcessor#process} never builds a non-empty {@link
 * io.repsy.libs.protocol.router.ProcessorResult}, on success or on failure.
 */
@DisplayName("UsagePostProcessor")
class UsagePostProcessorTest {

  private static final UUID STORAGE_KEY = UUID.randomUUID();

  private UsageUpdateService usageUpdateService;
  private UsagePostProcessor postProcessor;

  private static ProtocolContext contextWithUsage(final BaseUsages usages) {
    final var repoInfo = RepoInfo.builder().storageKey(STORAGE_KEY).name("repo").build();
    final var urlProperties =
        UrlParserProperties.builder()
            .repoName("repo")
            .relativePath(new RelativePath("/blobs/upload-id"))
            .repoInfo(repoInfo)
            .build();

    final var context = new ProtocolContext();
    context.addProperty("urlProperties", urlProperties);
    if (usages != null) {
      context.addProperty("usages", usages);
    }
    return context;
  }

  @BeforeEach
  void setUp() {
    this.usageUpdateService = mock(UsageUpdateService.class);
    this.postProcessor = new UsagePostProcessor(this.usageUpdateService, List.of());
  }

  @Test
  @DisplayName("opts in to running on a failed request")
  void runsOnFailure() {
    assertThat(this.postProcessor.runsOnFailure()).isTrue();
  }

  @Test
  @DisplayName("settles the usage a request (successful or not) put on the context")
  void settlesUsageOnTheContext() {
    final var context = contextWithUsage(BaseUsages.ofDisk(42));

    final var result =
        this.postProcessor.process(
            context, mock(HttpServletRequest.class), mock(HttpServletResponse.class), Map.of());

    assertThat(result.isEmpty()).isTrue();
    verify(this.usageUpdateService)
        .updateUsage(new UsageChangedInfo(STORAGE_KEY, BaseUsages.ofDisk(42)));
  }

  @Test
  @DisplayName("does nothing when the context carries no usage, e.g. a failure before any write")
  void skipsWhenNoUsageWasRecorded() {
    final var context = contextWithUsage(null);

    this.postProcessor.process(
        context, mock(HttpServletRequest.class), mock(HttpServletResponse.class), Map.of());

    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("does nothing for a zero net usage")
  void skipsZeroUsage() {
    final var context = contextWithUsage(BaseUsages.ofDisk(0));

    this.postProcessor.process(
        context, mock(HttpServletRequest.class), mock(HttpServletResponse.class), Map.of());

    verifyNoInteractions(this.usageUpdateService);
  }

  @Test
  @DisplayName("is skipped when the handler explicitly opted out via skipUsagePostProcessor")
  void honoursSkipProperty() {
    final var context = contextWithUsage(BaseUsages.ofDisk(42));

    this.postProcessor.process(
        context,
        mock(HttpServletRequest.class),
        mock(HttpServletResponse.class),
        Map.of("skipUsagePostProcessor", true));

    verify(this.usageUpdateService, never()).updateUsage(any());
  }
}
