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
package io.repsy.os.server.protocols.maven.shared.artifact.mappers;

import io.repsy.os.generated.model.ArtifactVersionInfo;
import io.repsy.os.server.protocols.maven.shared.artifact.entities.ArtifactVersion;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.stereotype.Component;

@Component
public class ArtifactVersionToArtifactVersionInfoConverter
    implements Converter<ArtifactVersion, ArtifactVersionInfo> {

  ArtifactVersionToArtifactVersionInfoConverter(
      final @NonNull GenericConversionService conversionService) {

    conversionService.addConverter(this);
  }

  @Override
  public ArtifactVersionInfo convert(final @Nullable ArtifactVersion source) {

    if (source == null) {
      return null;
    }

    return ArtifactVersionInfo.builder()
        .artifactGroupName(source.getArtifact().getGroupName())
        .artifactName(source.getArtifact().getArtifactName())
        .type(
            source.getType() != null
                ? ArtifactVersionInfo.TypeEnum.valueOf(source.getType().name())
                : null)
        .versionName(source.getVersionName())
        .lastUpdatedAt(source.getLastUpdatedAt())
        .signed(source.isSigned())
        .build();
  }
}
