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
package io.repsy.os.panel.profile.services;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.core.error_handling.exceptions.UnAuthorizedException;
import io.repsy.os.generated.model.PasswordForm;
import io.repsy.os.shared.auth.services.LoginInfoFactory;
import io.repsy.os.shared.constants.ErrorConstants;
import io.repsy.os.shared.user.mappers.UserConverter;
import io.repsy.os.shared.user.repositories.UserRepository;
import io.repsy.os.shared.user.services.ReservedUsernameService;
import io.repsy.os.shared.user.services.UserTxService;
import java.time.Instant;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * RPS-962: the profile endpoints resolve the caller from the token's user id. A valid token whose
 * user is gone is an authentication failure, not a 404.
 */
@DisplayName("ProfileService")
class ProfileServiceTest {

  // A real UserTxService over an empty repository: the lookup itself is under test.
  private final ProfileService service =
      new ProfileService(
          Mockito.mock(LoginInfoFactory.class),
          Mockito.mock(ReservedUsernameService.class),
          new UserTxService(Mockito.mock(UserRepository.class), Mockito.mock(UserConverter.class)));

  private final UUID ghostId = UUID.randomUUID();

  @Test
  @DisplayName("getProfile answers unAuthorized when the token's user no longer exists")
  void getProfile() {
    assertUnauthorized(() -> this.service.getProfile(this.ghostId));
  }

  @Test
  @DisplayName("updateUsername answers unAuthorized when the token's user no longer exists")
  void updateUsername() {
    assertUnauthorized(() -> this.service.updateUsername(this.ghostId, "newname", Instant.now()));
  }

  @Test
  @DisplayName("updatePassword answers unAuthorized when the token's user no longer exists")
  void updatePassword() {
    assertUnauthorized(
        () -> this.service.updatePassword(this.ghostId, new PasswordForm(), Instant.now()));
  }

  private static void assertUnauthorized(final ThrowingCallable call) {
    assertThatThrownBy(call)
        .isExactlyInstanceOf(UnAuthorizedException.class)
        .hasMessage(ErrorConstants.UN_AUTHORIZED);
  }
}
