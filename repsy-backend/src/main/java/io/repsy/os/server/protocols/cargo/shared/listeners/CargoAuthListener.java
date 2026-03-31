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
package io.repsy.os.server.protocols.cargo.shared.listeners;

import io.repsy.core.events.UserCreatedEvent;
import io.repsy.os.shared.repo.services.RepoTxService;
import io.repsy.protocols.cargo.shared.storage.services.CargoStorageService;
import io.repsy.protocols.shared.repo.dtos.RepoType;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@NullMarked
public class CargoAuthListener {

  private static final String CARGO_REPO_NAME = "cargo";

  private final RepoTxService repoTxService;
  private final CargoStorageService cargoStorageService;

  @Async
  @EventListener
  public void onRegistrationCompleted(final UserCreatedEvent<UUID> ignoredEvent) {

    final var repoInfo = this.repoTxService.createRepo(CARGO_REPO_NAME, RepoType.CARGO, true, null);

    this.cargoStorageService.createRepo(repoInfo.getStorageKey());
  }
}
