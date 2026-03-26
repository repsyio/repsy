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
package io.repsy.os.shared.auth.utils;

import java.util.List;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.jspecify.annotations.NonNull;
import org.passay.CharacterRule;
import org.passay.EnglishCharacterData;
import org.passay.PasswordGenerator;

@UtilityClass
public class PasswordGeneratorUtil {

  private static final int DEFAULT_PASSWORD_LENGTH = 12;
  private static final int SALT_LENGTH = 16;
  private static final String SPECIAL_CHARS = "!@#$%^&*";

  private static final List<CharacterRule> RULES =
      List.of(
          new CharacterRule(EnglishCharacterData.LowerCase, 1),
          new CharacterRule(EnglishCharacterData.UpperCase, 1),
          new CharacterRule(EnglishCharacterData.Digit, 1),
          new CharacterRule(
              new org.passay.CharacterData() {
                @Override
                public String getErrorCode() {
                  return "CUSTOM_SPECIAL";
                }

                @Override
                public String getCharacters() {
                  return SPECIAL_CHARS;
                }
              },
              1));

  public @NonNull String generatePassword() {
    return new PasswordGenerator().generatePassword(DEFAULT_PASSWORD_LENGTH, RULES);
  }

  public @NonNull String generateSalt() {
    return RandomStringUtils.secure().nextAlphanumeric(SALT_LENGTH);
  }

  public @NonNull String hashPassword(final @NonNull String password, final @NonNull String salt) {
    return DigestUtils.sha256Hex(password + salt);
  }
}
