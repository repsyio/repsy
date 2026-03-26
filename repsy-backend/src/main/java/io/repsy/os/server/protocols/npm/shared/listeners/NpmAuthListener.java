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
package io.repsy.os.server.protocols.npm.shared.listeners;

import io.repsy.core.events.UserCreatedEvent;
import io.repsy.os.server.protocols.npm.shared.storage.services.NpmStorageService;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class NpmAuthListener {
  private static final @NonNull String NPM_REPO_NAME = "npm";

  private final @NonNull RepoTxService repoTxService;
  private final @NonNull NpmStorageService npmStorageService;

  @Async
  @EventListener
  public void onRegistrationCompleted(final @NonNull UserCreatedEvent<UUID> ignoredEvent) {

    final var repoInfo = this.repoTxService.createRepo(NPM_REPO_NAME, RepoType.NPM, true, null);

    this.npmStorageService.createRepo(repoInfo.getStorageKey());
  }
}
