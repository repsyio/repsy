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
package io.repsy.os.server.protocols.maven.ui.controllers;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.MavenGroupSummary;
import io.repsy.os.server.protocols.maven.shared.artifact.services.ArtifactServiceImpl;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.utils.MultiPortNames;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A Maven group as a whole. It is its own resource, not a sub-path of {@code /api/mvn/artifacts},
 * because {@code /{repoName}/{groupName}/{artifactName}} there already means an artifact, and an
 * artifact may be named like any word.
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/mvn/groups")
@NullMarked
@SuppressWarnings("java:S6856")
public class MavenGroupController {

  private final ArtifactServiceImpl artifactService;
  private final RestResponseFactory restResponseFactory;

  @GetMapping("/{repoName}/{groupName}")
  @RepoOperation
  public RestResponse<MavenGroupSummary> getSummary(
      final RepoInfo repoInfo, @PathVariable final String groupName) {

    final var summary = this.artifactService.getGroupSummary(repoInfo.getStorageKey(), groupName);

    return this.restResponseFactory.success("groupSummaryFetched", summary);
  }
}
