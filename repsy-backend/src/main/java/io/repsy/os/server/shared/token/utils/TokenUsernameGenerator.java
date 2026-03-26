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
package io.repsy.os.server.shared.token.utils;

import static org.apache.commons.lang3.RandomStringUtils.random;

import io.repsy.os.server.shared.token.dtos.TokenUsernameType;
import java.security.SecureRandom;
import java.util.Locale;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NonNull;

@UtilityClass
public class TokenUsernameGenerator {

  private static final @NonNull SecureRandom SECURE_RANDOM = new SecureRandom();
  private static final int TOKEN_USERNAME_LENGTH = 7;

  private static @NonNull String of(final @NonNull TokenUsernameType tokenUsernameType) {

    return generateTokenUsername(tokenUsernameType);
  }

  public static @NonNull String deployTokenUsername() {

    return of(TokenUsernameType.REPO_DEPLOY_TOKEN_USERNAME);
  }

  private static @NonNull String generateTokenUsername(
      final @NonNull TokenUsernameType tokenUsernameType) {

    final var randomString = random(TOKEN_USERNAME_LENGTH, 0, 0, true, true, null, SECURE_RANDOM);

    return tokenUsernameType.getPrefix() + randomString.toLowerCase(Locale.ROOT);
  }
}
