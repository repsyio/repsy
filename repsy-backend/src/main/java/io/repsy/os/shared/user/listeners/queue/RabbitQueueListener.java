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
package io.repsy.os.shared.user.listeners.queue;

import dev.gitnode.os.events.queue.AccountDeletedEvent;
import dev.gitnode.os.events.queue.PasswordUpdatedEvent;
import dev.gitnode.os.events.queue.QueueBaseMessage;
import dev.gitnode.os.events.queue.TenantRegisteredEvent;
import dev.gitnode.os.events.queue.UsernameUpdatedEvent;
import dev.gitnode.os.events.queue.visitor.QueueEventVisitor;
import io.repsy.os.shared.auth.utils.PasswordGeneratorUtil;
import io.repsy.os.shared.configs.queue.GitNodeIdempotency;
import io.repsy.os.shared.configs.queue.GitNodeIdempotencyRepository;
import io.repsy.os.shared.user.entities.UserRole;
import io.repsy.os.shared.user.services.UserTxService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

@Service
@NullMarked
@RequiredArgsConstructor
public class RabbitQueueListener implements QueueEventVisitor {

  private final UserTxService userTxService;
  private final GitNodeIdempotencyRepository idempotencyRepository;

  @RabbitListener(queues = "user-management")
  public void onEventHandled(final QueueBaseMessage message) {

    final var idempotencyKey = message.getIdempotencyKey();

    if (this.existsIdempotencyKey(idempotencyKey)) {
      return;
    }

    message.accept(this);

    this.idempotencyRepository.save(new GitNodeIdempotency(idempotencyKey));
  }

  private boolean existsIdempotencyKey(final UUID idempotencyKey) {

    return this.idempotencyRepository.existsByKey(idempotencyKey);
  }

  @Override
  public void visit(final TenantRegisteredEvent event) {

    final var hash = PasswordGeneratorUtil.hashPassword(event.getPassword(), event.getSalt());

    this.userTxService.create(event.getUsername(), UserRole.USER, hash, event.getSalt());
  }

  @Override
  public void visit(final PasswordUpdatedEvent event) {

    final var user = this.userTxService.getUserByUsername(event.getUsername());

    final var hash = PasswordGeneratorUtil.hashPassword(event.getPassword(), event.getSalt());

    this.userTxService.updatePassword(user.getId(), hash, event.getSalt());
  }

  @Override
  public void visit(final UsernameUpdatedEvent event) {

    final var user = this.userTxService.getUserByUsername(event.getOldUsername());

    this.userTxService.updateUsername(user.getId(), event.getNewUsername());
  }

  @Override
  public void visit(final AccountDeletedEvent event) {

    final var user = this.userTxService.getUserByUsername(event.getUsername());

    this.userTxService.deleteUserById(user.getId());
  }
}
