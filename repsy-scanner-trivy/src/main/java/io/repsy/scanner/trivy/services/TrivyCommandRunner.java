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
package io.repsy.scanner.trivy.services;

import java.util.List;
import org.jspecify.annotations.NonNull;

/** Runs one trivy command line and returns its standard output. Tests replace it by a fake. */
public interface TrivyCommandRunner {

  /**
   * @throws io.repsy.scanner.trivy.errors.TrivyTimeoutException when the command runs longer than
   *     {@code timeoutSeconds}
   * @throws io.repsy.scanner.trivy.errors.TrivyScanException when the command cannot be started or
   *     exits with a non-zero code
   */
  @NonNull String run(@NonNull List<String> command, long timeoutSeconds);
}
