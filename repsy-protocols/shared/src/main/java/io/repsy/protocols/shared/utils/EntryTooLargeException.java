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
package io.repsy.protocols.shared.utils;

import org.jspecify.annotations.NullMarked;

/**
 * Thrown by {@link BoundedEntryReader} when an archive entry inflates past the allowed size, and by
 * {@link SpooledUpload} when an upload is larger than the allowed size.
 */
@NullMarked
public class EntryTooLargeException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  private final long maxBytes;

  public EntryTooLargeException(final long maxBytes) {
    super("entry is larger than %d bytes".formatted(maxBytes));
    this.maxBytes = maxBytes;
  }

  /** The limit the entry exceeded, in bytes. */
  public long getMaxBytes() {
    return this.maxBytes;
  }
}
