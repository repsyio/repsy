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
package io.repsy.protocols.golang.shared.module.validators;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;

@UtilityClass
@NullMarked
public class GoModFileValidator {

  private static final Pattern MODULE_DIRECTIVE =
      Pattern.compile("^module\\s+\\S+", Pattern.MULTILINE);
  private static final Pattern MODULE_PATH =
      Pattern.compile("^module\\s+(\\S+)", Pattern.MULTILINE);

  /**
   * Validates a {@code go.mod}'s content and, once it is otherwise well-formed, that its {@code
   * module} directive names the same module the upload's URL path is for (RPS-1228). {@code
   * expectedModulePath} is the URL's decoded module path, not the lower-cased one it is stored
   * under: {@code GoModuleZipReader} already matches the zip's own entry prefix against that same
   * decoded path, so both checks stay consistent with each other.
   */
  public static void validate(final byte[] content, final String expectedModulePath) {
    if (content.length == 0) {
      throw new BadRequestException("goModFileEmpty");
    }

    final var text = new String(content, StandardCharsets.UTF_8);

    if (!MODULE_DIRECTIVE.matcher(text).find()) {
      throw new BadRequestException("goModMissingModuleDirective");
    }

    validateModulePath(text);
    validateDeclaredPath(text, expectedModulePath);
  }

  private static void validateModulePath(final String text) {
    final var matcher = MODULE_PATH.matcher(text);
    if (!matcher.find()) {
      return;
    }
    final var firstSegment = matcher.group(1).split("/", -1)[0];
    if (!firstSegment.contains(".")) {
      throw new BadRequestException("goModInvalidModulePath");
    }
  }

  private static void validateDeclaredPath(final String text, final String expectedModulePath) {
    final var matcher = MODULE_PATH.matcher(text);
    if (!matcher.find()) {
      return; // already refused above by MODULE_DIRECTIVE
    }
    final var declared = unquote(matcher.group(1));
    if (!declared.equals(expectedModulePath)) {
      throw new BadRequestException("goModModulePathMismatch");
    }
  }

  /** {@code cmd/go}'s own parser accepts a quoted {@code module "example.com/foo"} directive. */
  private static String unquote(final String value) {
    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
      return value.substring(1, value.length() - 1);
    }
    return value;
  }
}
