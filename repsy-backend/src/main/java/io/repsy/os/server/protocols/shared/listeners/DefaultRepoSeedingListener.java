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
package io.repsy.os.server.protocols.shared.listeners;

import io.repsy.core.events.UserCreatedEvent;
import io.repsy.os.shared.repo.dtos.DefaultRepoDefinition;
import io.repsy.os.shared.repo.services.DefaultRepoSeeder;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Seeds the default repository of every protocol when a user is created (RPS-2062). The protocols
 * are the {@link DefaultRepoDefinition} beans, so adding a protocol is one bean, not one listener.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@NullMarked
public class DefaultRepoSeedingListener {

  private final DefaultRepoSeeder defaultRepoSeeder;
  private final List<DefaultRepoDefinition> definitions;

  @Async
  @EventListener
  public void onUserCreated(final UserCreatedEvent<UUID> ignoredEvent) {
    for (final var definition : this.definitions) {
      try {
        this.defaultRepoSeeder.seed(
            definition.name(), definition.type(), definition.storageCreator());
      } catch (final RuntimeException e) {
        // One protocol's failure must not keep the others from being seeded.
        log.error(
            "Seeding the default {} repo '{}' failed", definition.type(), definition.name(), e);
      }
    }
  }
}
