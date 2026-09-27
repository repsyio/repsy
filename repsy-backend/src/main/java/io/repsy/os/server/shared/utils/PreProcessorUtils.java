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
package io.repsy.os.server.shared.utils;

import io.repsy.os.shared.repo.dtos.RepoInfo;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NonNull;

/**
 * RPS-1576: {@code shouldSkipAuthentication} used to be copy-pasted, byte-for-byte identical, into
 * {@code CargoAuthPreProcessor}, {@code NuGetAuthPreProcessor} and {@code HelmAuthPreProcessor},
 * and {@code RubyAuthPreProcessor} reached across protocol module boundaries to call Cargo's copy
 * directly. This is the one shared implementation, in this module's {@code shared} (OPEN) home so
 * every protocol pre-processor can use it without depending on another protocol's internals.
 */
@UtilityClass
public class PreProcessorUtils {

  /**
   * A request skips authentication when the caller asked to (the {@code skipKey} property), or when
   * the repo is public and the operation is not a write.
   */
  public static boolean shouldSkipAuthentication(
      final @NonNull String skipKey,
      final @NonNull String writeKey,
      final @NonNull RepoInfo repoInfo,
      final @NonNull Map<String, Object> properties) {

    final var skipPreProcessor = (boolean) properties.getOrDefault(skipKey, false);

    if (skipPreProcessor) {
      return true;
    }

    final var writeOperation = (boolean) properties.getOrDefault(writeKey, false);

    return !repoInfo.isPrivateRepo() && !writeOperation;
  }
}
