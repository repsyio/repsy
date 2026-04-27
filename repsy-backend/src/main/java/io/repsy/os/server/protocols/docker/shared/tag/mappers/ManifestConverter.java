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
package io.repsy.os.server.protocols.docker.shared.tag.mappers;

import io.repsy.os.server.protocols.docker.shared.tag.dtos.manifest.ManifestDetail;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Manifest;
import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.jspecify.annotations.NullMarked;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
@NullMarked
public interface ManifestConverter {

  default Instant map(final LocalDateTime localDateTime) {
    return localDateTime.toInstant(ZoneOffset.UTC);
  }

  ManifestDetail toManifestDetail(Manifest manifest);

  @Mapping(target = "lastUpdatedAt", source = "lastUpdatedAt")
  io.repsy.os.generated.model.ImageTagListItem toTagDto(
      io.repsy.os.server.protocols.docker.shared.tag.dtos.ImageTagListItem source);

  @Mapping(target = "createdAt", source = "createdAt")
  io.repsy.os.generated.model.ManifestListItem toManifestDto(
      io.repsy.os.server.protocols.docker.shared.layer.dtos.ManifestListItem source);

  @Mapping(target = "configDigest", ignore = true)
  @Mapping(target = "imageName", source = "image.name")
  io.repsy.os.generated.model.TagDetail toTagDetailBase(Tag tag);

  default io.repsy.os.generated.model.TagDetail toTagDetail(final Tag tag) {

    final var tagDetail = this.toTagDetailBase(tag);

    if (!io.repsy.protocols.docker.shared.utils.MediaTypes.isIndex(tag.getMediaType())) {
      final var platformIterator = tag.getTagPlatforms().iterator();
      if (platformIterator.hasNext()) {
        final var manifestIterator = platformIterator.next().getManifests().iterator();
        if (manifestIterator.hasNext()) {
          tagDetail.setConfigDigest(manifestIterator.next().getConfigDigest());
        }
      }
    }

    return tagDetail;
  }
}
