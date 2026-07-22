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
package io.repsy.os.server.protocols.docker.shared.image.mappers;

import io.repsy.os.server.protocols.docker.shared.image.dtos.ImageInfo;
import io.repsy.os.server.protocols.docker.shared.image.entities.Image;
import java.time.Instant;
import java.time.ZoneOffset;
import org.jspecify.annotations.NullMarked;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Mappings;

@Mapper(componentModel = "spring")
@NullMarked
public interface ImageConverter {

  /**
   * {@code updatedAt} reflects the most recent tag push (intentional — that is what "last updated"
   * means for an image), which is {@code null} when the image has no tags left. Falls back to the
   * image's own {@code lastUpdatedAt} so a zero-tag image never produces a {@code null} timestamp.
   */
  default Instant resolveUpdatedAt(
      final io.repsy.os.server.protocols.docker.shared.image.dtos.ImageListItem source) {
    final var lastTagPushedAt = source.getUpdatedAt();
    return lastTagPushedAt == null
        ? source.getLastUpdatedAt()
        : lastTagPushedAt.toInstant(ZoneOffset.UTC);
  }

  @Mappings({
    @Mapping(target = "repoId", source = "repo.id"),
    @Mapping(target = "repoName", source = "repo.name")
  })
  ImageInfo toImageInfo(Image image);

  @Mapping(target = "updatedAt", expression = "java(resolveUpdatedAt(source))")
  io.repsy.os.generated.model.ImageListItem toDto(
      io.repsy.os.server.protocols.docker.shared.image.dtos.ImageListItem source);
}
