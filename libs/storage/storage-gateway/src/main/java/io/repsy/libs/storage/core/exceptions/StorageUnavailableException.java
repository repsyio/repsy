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
package io.repsy.libs.storage.core.exceptions;

import java.io.IOException;

/**
 * The storage could not write, create or move an object: the file system or the object store
 * answered the write with an {@link IOException} (RPS-2104). The request changed nothing that it
 * keeps, so the same request may succeed a moment later; {@code ErrorHandler} answers it 503 with
 * {@code Retry-After}.
 *
 * <p>Only the storage strategies throw it, and only for the storage side of a write. A failure to
 * read the client's request body, and every read of a stored object, keep their own exceptions: a
 * client that went away is not a storage outage, and a missing object is a 404.
 */
public final class StorageUnavailableException extends RuntimeException {

  public StorageUnavailableException(final String message, final IOException cause) {
    super(message, cause);
  }
}
