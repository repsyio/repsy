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

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.os.panel.profile.repositories.ReservedUsernameRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

@DisplayName("ReservedUsernameService")
class ReservedUsernameServiceTest {

  private final ReservedUsernameRepository repository =
      Mockito.mock(ReservedUsernameRepository.class);
  private final ReservedUsernameService service = new ReservedUsernameService(this.repository);

  @Test
  @DisplayName("requireNotReserved answers usernameInUse for a reserved name")
  void reserved() {
    when(this.repository.existsByUsernameIgnoreCase("Repsy")).thenReturn(true);

    assertThatThrownBy(() -> this.service.requireNotReserved("Repsy"))
        .isExactlyInstanceOf(BadRequestException.class)
        .hasMessage("usernameInUse");
  }

  @Test
  @DisplayName("requireNotReserved passes the name to the case-insensitive lookup as typed")
  void looksUpIgnoringCase() {
    this.service.requireNotReserved("DoCkEr");

    verify(this.repository).existsByUsernameIgnoreCase("DoCkEr");
  }

  @Test
  @DisplayName("requireNotReserved accepts a name that is not reserved")
  void notReserved() {
    when(this.repository.existsByUsernameIgnoreCase("alice")).thenReturn(false);

    assertThatCode(() -> this.service.requireNotReserved("alice")).doesNotThrowAnyException();
  }
}
