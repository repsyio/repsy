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

import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.DigestUtils;
import org.jspecify.annotations.NonNull;

/**
 * The hash a token is stored and looked up by: SHA-256 of the whole token, as lower-case hex (64
 * characters), without a salt.
 *
 * <p>A salt would protect a low-entropy secret from a dictionary, and a token is not one: {@link
 * TokenFactory} makes it from a SHA-256 of 32 random alphanumeric characters, so there is nothing
 * to guess. A salt would also make it impossible to look a token up by its hash, which is how every
 * request that presents one finds it. {@code DeployTokenHash} makes the same choice for deploy
 * tokens.
 */
@UtilityClass
public class TokenHash {

  public static @NonNull String hash(final @NonNull String token) {
    return DigestUtils.sha256Hex(token);
  }
}
