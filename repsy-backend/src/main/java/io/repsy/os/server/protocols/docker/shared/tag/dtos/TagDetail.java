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
package io.repsy.os.server.protocols.docker.shared.tag.dtos;

import io.repsy.os.server.protocols.docker.shared.tag.entities.Tag;
import io.repsy.protocols.docker.shared.tag.dtos.BaseTagDetail;
import java.util.UUID;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

@Getter
@Setter
@SuperBuilder
public class TagDetail extends BaseTagDetail<UUID> {

  public static @NonNull TagDetail of(final @NonNull Tag tag) {

    return TagDetail.builder()
        .id(tag.getId())
        .name(tag.getName())
        .digest(tag.getDigest())
        .platform(tag.getPlatform())
        .mediaType(tag.getMediaType())
        .createdAt(tag.getCreatedAt())
        .lastUpdatedAt(tag.getLastUpdatedAt())
        .build();
  }

  public static @NonNull TagDetail of(final @NonNull Tag tag, final @Nullable String configDigest) {

    final var tagDetail = of(tag);
    tagDetail.setConfigDigest(configDigest);

    return tagDetail;
  }
}
