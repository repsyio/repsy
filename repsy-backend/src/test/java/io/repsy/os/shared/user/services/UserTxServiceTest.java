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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.repsy.core.error_handling.exceptions.ItemNotFoundException;
import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.dtos.UserInfo;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

@DisplayName("UserTxService")
class UserTxServiceTest {

  private final UserRepository userRepository = Mockito.mock(UserRepository.class);
  private final UserConverter userConverter = Mockito.mock(UserConverter.class);
  private final UserTxService service = new UserTxService(this.userRepository, this.userConverter);

  @Test
  @DisplayName("getAuthenticatedUserByUsername returns the user")
  void byUsernameReturnsUser() {
    final var user = new User();
    final var userInfo = UserInfo.builder().username("alice").build();
    when(this.userRepository.findByUsername("alice")).thenReturn(Optional.of(user));
    when(this.userConverter.toUserInfo(user)).thenReturn(userInfo);

    assertThat(this.service.getAuthenticatedUserByUsername("alice")).isSameAs(userInfo);
  }

  @Test
  @DisplayName("getAuthenticatedUserByUsername answers unAuthorized for an unknown username")
  void byUsernameUnknown() {
    assertThatThrownBy(() -> this.service.getAuthenticatedUserByUsername("ghost"))
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }

  @Test
  @DisplayName("getAuthenticatedUserById returns the user")
  void byIdReturnsUser() {
    final var id = UUID.randomUUID();
    final var user = new User();
    final var userInfo = UserInfo.builder().id(id).build();
    when(this.userRepository.findById(id)).thenReturn(Optional.of(user));
    when(this.userConverter.toUserInfo(user)).thenReturn(userInfo);

    assertThat(this.service.getAuthenticatedUserById(id)).isSameAs(userInfo);
  }

  @Test
  @DisplayName("getAuthenticatedUserById answers unAuthorized for an unknown id")
  void byIdUnknown() {
    assertThatThrownBy(() -> this.service.getAuthenticatedUserById(UUID.randomUUID()))
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }

  @Test
  @DisplayName("getUserByUsername keeps answering userNotFound for a plain lookup")
  void plainLookupStillNotFound() {
    assertThatThrownBy(() -> this.service.getUserByUsername("ghost"))
        .isExactlyInstanceOf(ItemNotFoundException.class)
        .hasMessage("userNotFound");
  }

  @Test
  @DisplayName("updateLastLoginAt writes the timestamp through a single-column update")
  void lastLoginIsWrittenWithoutLoadingTheUser() {
    when(this.userRepository.updateLastLoginAt(eq("alice"), any(Instant.class))).thenReturn(1);
    final var before = Instant.now();

    this.service.updateLastLoginAt("alice");

    final var written = ArgumentCaptor.forClass(Instant.class);
    verify(this.userRepository).updateLastLoginAt(eq("alice"), written.capture());
    assertThat(written.getValue()).isBetween(before, Instant.now());
    verify(this.userRepository, never()).findByUsername(any());
    verify(this.userRepository, never()).save(any());
  }

  @Test
  @DisplayName("updateLastLoginAt answers userNotFound when no user has the username")
  void lastLoginForUnknownUser() {
    when(this.userRepository.updateLastLoginAt(eq("ghost"), any(Instant.class))).thenReturn(0);

    assertThatThrownBy(() -> this.service.updateLastLoginAt("ghost"))
        .isExactlyInstanceOf(ItemNotFoundException.class)
        .hasMessage("userNotFound");
  }
}
