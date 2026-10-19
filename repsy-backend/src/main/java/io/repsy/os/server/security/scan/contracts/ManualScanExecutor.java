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
package io.repsy.os.server.security.scan.contracts;

import io.repsy.core.events.ArtifactPushedEvent;
import java.util.UUID;

/**
 * Runs the scan that a manual trigger prepared. The scan module owns this port; {@code
 * ArtifactScanListener} in {@code security.shared} implements it, so the scan module no longer
 * depends on the shared one (RPS-2278).
 */
public interface ManualScanExecutor {

  void executeManualScan(UUID scanId, ArtifactPushedEvent event);
}
