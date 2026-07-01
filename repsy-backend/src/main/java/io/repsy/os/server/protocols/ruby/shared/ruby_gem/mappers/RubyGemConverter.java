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
package io.repsy.os.server.protocols.ruby.shared.ruby_gem.mappers;

import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemInfo;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemListItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemVersionListItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGemDependency;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.entities.RubyGemVersion;
import io.repsy.os.shared.repo.entities.Repo;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
@NullMarked
public interface RubyGemConverter {

  io.repsy.os.generated.model.GemListItem toGemListItemDto(GemListItem source);

  io.repsy.os.generated.model.GemVersionListItem toGemVersionListItemDto(GemVersionListItem source);

  io.repsy.os.generated.model.GemDependencyInfo toDependencyInfoDto(RubyGemDependency entity);

  default io.repsy.os.generated.model.GemVersionInfo toGemVersionInfoDto(
      final RubyGemVersion version, final List<RubyGemDependency> deps) {

    final var runtimeDeps =
        deps.stream()
            .filter(d -> "runtime".equals(d.getType()))
            .map(this::toDependencyInfoDto)
            .toList();

    final var devDeps =
        deps.stream()
            .filter(d -> !"runtime".equals(d.getType()))
            .map(this::toDependencyInfoDto)
            .toList();

    return io.repsy.os.generated.model.GemVersionInfo.builder()
        .id(version.getId())
        .version(version.getVersion())
        .platform(version.getPlatform())
        .checksum(version.getChecksum())
        .authors(version.getAuthors())
        .description(version.getDescription())
        .homepage(version.getHomepage())
        .requiredRubyVersion(version.getRequiredRubyVersion())
        .yanked(version.isYanked())
        .createdAt(version.getCreatedAt())
        .runtimeDependencies(runtimeDeps)
        .developmentDependencies(devDeps)
        .build();
  }

  default GemInfo toGemInfo(final RubyGem gem, final Repo repo) {
    return GemInfo.builder()
        .id(gem.getId())
        .repoName(repo.getName())
        .name(gem.getName())
        .latest(gem.getLatest())
        .createdAt(gem.getCreatedAt())
        .build();
  }
}
