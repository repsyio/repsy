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
package io.repsy.os.server.protocols.docker.protocol.utils;

import io.repsy.os.server.protocols.docker.shared.layer.services.LayerTxService;
import io.repsy.protocols.docker.shared.layer.services.AbstractDockerLayerRenamer;
import io.repsy.protocols.docker.shared.storage.services.DockerStorageService;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@NullMarked
public class DockerLayerRenamer extends AbstractDockerLayerRenamer<UUID> {

  public DockerLayerRenamer(
      final DockerStorageService<UUID> dockerStorageService,
      final LayerTxService layerTxService,
      final ObjectMapper objectMapper) {
    super(dockerStorageService, layerTxService, objectMapper);
  }
}
