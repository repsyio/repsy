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
package io.repsy.os.server.protocols.shared.configs;

import java.time.Duration;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How long a deleted repo, package or version stays in the trash of the filesystem storage before
 * {@code StorageTrashCleanupTask} removes it for good. One value serves the trash of every
 * protocol.
 *
 * <p>It is at least one day. The trash is grouped in one directory per day, and a delete always
 * moves into today's directory, so a retention below a day would let the cleanup remove the
 * directory a concurrent delete is writing to. An application whose retention is lower does not
 * start.
 *
 * @param trashRetention how long deleted items are kept, {@code P7D} unless configured
 */
@ConfigurationProperties(prefix = "os.app.storage.file-system")
public record StorageTrashProperties(@DefaultValue("P7D") @NonNull Duration trashRetention) {

  public static final Duration MIN_TRASH_RETENTION = Duration.ofDays(1);

  public StorageTrashProperties {
    if (trashRetention.compareTo(MIN_TRASH_RETENTION) < 0) {
      throw new IllegalArgumentException(
          "os.app.storage.file-system.trash-retention (TRASH_RETENTION) must be at least P1D, but"
              + " was "
              + trashRetention);
    }
  }
}
