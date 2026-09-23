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
package io.repsy.protocols.golang.shared.module.services;

import io.repsy.libs.storage.core.dtos.BaseUsages;
import java.io.IOException;
import org.jspecify.annotations.NullMarked;

/**
 * Writes the files of a Go module version. Run by {@link GoModuleService#publishModule} while the
 * version's row is written but not yet committed: a failure rolls the row back.
 */
@FunctionalInterface
@NullMarked
public interface GoModuleFilesWriter {

  /**
   * @return the usages of the files written
   */
  BaseUsages write() throws IOException;
}
