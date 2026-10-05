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
package io.repsy.os.server.protocols.docker.shared.cleanup.entities;

import java.time.Duration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** How often a cleanup policy runs. */
@Getter
@RequiredArgsConstructor
public enum CleanupCadence {
  DAILY(Duration.ofDays(1)),
  WEEKLY(Duration.ofDays(7)),
  BIWEEKLY(Duration.ofDays(14)),
  MONTHLY(Duration.ofDays(30)),
  QUARTERLY(Duration.ofDays(90));

  private final Duration duration;
}
