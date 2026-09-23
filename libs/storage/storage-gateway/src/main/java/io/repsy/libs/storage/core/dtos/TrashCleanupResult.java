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
package io.repsy.libs.storage.core.dtos;

import org.jspecify.annotations.NonNull;

/**
 * The outcome of one {@code StorageStrategy.clearTrash()} pass: what was permanently removed from
 * the trash because it was older than the retention period.
 *
 * @param directoriesDeleted the trash directories removed for good, counting each date directory
 *     and every subdirectory nested under it
 * @param filesDeleted the files removed for good
 * @param bytesFreed the total size in bytes of the files removed
 */
public record TrashCleanupResult(int directoriesDeleted, int filesDeleted, long bytesFreed) {

  /** The result of a pass that found nothing old enough to remove. */
  public static final @NonNull TrashCleanupResult EMPTY = new TrashCleanupResult(0, 0, 0);

  /** Answers the combined totals of this result and {@code other}. */
  public @NonNull TrashCleanupResult plus(final @NonNull TrashCleanupResult other) {
    return new TrashCleanupResult(
        this.directoriesDeleted + other.directoriesDeleted,
        this.filesDeleted + other.filesDeleted,
        this.bytesFreed + other.bytesFreed);
  }
}
