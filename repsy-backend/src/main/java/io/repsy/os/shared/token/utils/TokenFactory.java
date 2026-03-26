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
package io.repsy.os.shared.token.utils;

import io.repsy.os.shared.token.dtos.TokenType;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NonNull;

@UtilityClass
public class TokenFactory {

  public static @NonNull String of(final @NonNull TokenType tokenType) {

    return TokenGenerator.generate(tokenType);
  }

  public static @NonNull String deployToken() {

    return of(TokenType.REPSY_DEPLOY_TOKEN);
  }
}
