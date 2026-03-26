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
package io.repsy.protocols.docker.shared.tag.dtos;

import java.util.List;
import java.util.stream.Collectors;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.NonNull;

@Data
@NoArgsConstructor
public class ManifestInfo {
  private long schemaVersion;
  private String mediaType;
  private Config config;
  private List<ManifestLayer> layers;

  public @NonNull List<String> getLayerDigests() {

    // should be mutable.
    return this.layers.stream().map(ManifestLayer::getDigest).collect(Collectors.toList());
  }

  public @NonNull String getConfigDigest() {

    return this.config.getDigest();
  }
}
