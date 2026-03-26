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
package io.repsy.os.shared.repo.utils;

import io.repsy.core.error_handling.exceptions.AccessNotAllowedException;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;
import org.jspecify.annotations.NonNull;

@UtilityClass
public class RepoUtils {
  private static final @NonNull Pattern REPO_NAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_\\-]+$");

  public void validateRepoName(final @NonNull String repoName) {
    if (!REPO_NAME_PATTERN.matcher(repoName).matches()) {
      throw new AccessNotAllowedException("invalidRequest");
    }
  }
}
