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
package io.repsy.os.shared.constants;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.shared.constants.ProtocolErrorCodes;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ErrorConstants")
class ErrorConstantsTest {

  /** The codes only the panel and the backend emit, pinned: they are wire values (RPS-2017). */
  private static final Set<String> PINNED =
      Set.of(
          "accessTokenExpirationInPast",
          "accessTokenExpirationTooLate",
          "accessTokenLimitReached",
          "accessTokenNotFound",
          "cannotDeleteLastAdminUser",
          "cannotDemoteLastAdminUser",
          "downloadTokenExpired",
          "invalidAuthType",
          "invalidCredentials",
          "notAnAccessToken",
          "passwordTooLong",
          "pgpSettingsUnsupported",
          "refreshTokenExpired",
          "releasesSnapshotsUnsupported",
          "repoExists",
          "repoNameReserved",
          "sessionExpired",
          "userNotFound",
          "usernameInUse");

  static List<String> values(final Class<?> holder) throws IllegalAccessException {
    final var values = new ArrayList<String>();
    for (final Field field : holder.getDeclaredFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == String.class) {
        values.add((String) field.get(null));
      }
    }
    return values;
  }

  @Test
  @DisplayName("holds exactly the pinned codes")
  void holdsThePinnedCodes() throws IllegalAccessException {
    assertThat(values(ErrorConstants.class)).containsExactlyInAnyOrderElementsOf(PINNED);
  }

  @Test
  @DisplayName("shares no code with ProtocolErrorCodes: one string, one holder")
  void sharesNothingWithProtocolErrorCodes() throws IllegalAccessException {
    assertThat(values(ErrorConstants.class))
        .doesNotContainAnyElementsOf(values(ProtocolErrorCodes.class));
  }
}
