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
package io.repsy.os.shared.user.services;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.os.panel.profile.repositories.ReservedUsernameRepository;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Guards the usernames that the {@code reserved_username} table holds back. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ReservedUsernameService {

  private final @NonNull ReservedUsernameRepository reservedUsernameRepository;

  /**
   * Rejects a reserved username with the same {@code usernameInUse} error a taken one gets, so the
   * response does not tell the two apart.
   */
  public void requireNotReserved(final @NonNull String username) {

    if (this.reservedUsernameRepository.existsByUsername(username)) {
      throw new BadRequestException("usernameInUse");
    }
  }
}
