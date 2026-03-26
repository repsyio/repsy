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
package io.repsy.protocols.npm.shared.utils;

import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@UtilityClass
@NullMarked
public class ExtractPath {

  public static NpmPathVars extractPathVars(final String packagePath) {
    String scopeName = null;
    var packageName = packagePath;

    if (packagePath.startsWith("@")) {
      final var parts = packagePath.substring(1).split("/", 2);
      if (parts.length == 2) {
        scopeName = parts[0];
        packageName = parts[1];
      }
    }

    return new NpmPathVars(scopeName, packageName);
  }

  public record NpmPathVars(@Nullable String scopeName, String packageName) {}
}
