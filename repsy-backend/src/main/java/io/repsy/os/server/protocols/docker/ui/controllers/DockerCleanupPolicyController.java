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
package io.repsy.os.server.protocols.docker.ui.controllers;

import io.repsy.core.web.http.ResponseEntities;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.generated.model.CleanupPolicyForm;
import io.repsy.os.generated.model.CleanupPolicyItem;
import io.repsy.os.generated.model.CleanupPolicyStatusForm;
import io.repsy.os.server.protocols.docker.shared.cleanup.services.CleanupPolicyService;
import io.repsy.os.server.protocols.shared.aop.config.RepoOperation;
import io.repsy.os.shared.repo.dtos.RepoInfo;
import io.repsy.os.shared.utils.MultiPortNames;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.repo.dtos.RepoScope;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Docker cleanup policy of a repo (RPS-1882). The same routes as Repsy Cloud's, without {@code
 * {repoOwner}}.
 */
@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/repos/{repoName}/docker/cleanup-policy")
@SuppressWarnings("java:S6856")
public class DockerCleanupPolicyController {

  private final CleanupPolicyService policyService;

  @GetMapping
  @RepoOperation(scope = RepoScope.DOCKER, permission = Permission.MANAGE)
  public CleanupPolicyItem get(final RepoInfo repoInfo) {

    return this.policyService.getCleanupPolicy(repoInfo.getStorageKey());
  }

  @PutMapping
  @RepoOperation(scope = RepoScope.DOCKER, permission = Permission.MANAGE)
  public CleanupPolicyItem update(
      final RepoInfo repoInfo, @Valid @RequestBody final CleanupPolicyForm form) {

    return this.policyService.updatePolicy(repoInfo.getStorageKey(), form);
  }

  @PatchMapping("/status")
  @RepoOperation(scope = RepoScope.DOCKER, permission = Permission.MANAGE)
  public CleanupPolicyItem patchStatus(
      final RepoInfo repoInfo, @Valid @RequestBody final CleanupPolicyStatusForm form) {

    return this.policyService.enableOrDisablePolicy(
        repoInfo.getStorageKey(), Boolean.TRUE.equals(form.getEnabled()));
  }

  @PostMapping("/actions/run")
  @RepoOperation(scope = RepoScope.DOCKER, permission = Permission.MANAGE)
  public ResponseEntity<Void> run(final RepoInfo repoInfo) {

    this.policyService.requestRun(repoInfo.getStorageKey());

    return ResponseEntities.accepted(
        URI.create("/api/repos/" + repoInfo.getName() + "/docker/cleanup-policy"));
  }
}
