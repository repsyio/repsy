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
package io.repsy.os.server.protocols.golang.shared.go_module.mappers;

import java.util.List;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface GoModuleMapper {

  @Mapping(
      target = "createdAt",
      expression =
          "java(source.getCreatedAt() != null ? source.getCreatedAt().atOffset(java.time.ZoneOffset.UTC) : null)")
  io.repsy.os.generated.model.GoModuleListItem toDto(
      io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleListItem source);

  @Mapping(
      target = "createdAt",
      expression =
          "java(source.getCreatedAt() != null ? source.getCreatedAt().atOffset(java.time.ZoneOffset.UTC) : null)")
  io.repsy.os.generated.model.GoModuleVersionListItem toVersionDto(
      io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleVersionListItem source);

  default io.repsy.os.generated.model.GoModuleInfo toGoModuleInfo(
      io.repsy.os.server.protocols.golang.shared.go_module.entities.GoModule goModule,
      List<io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleVersionListItem>
          versions) {

    final String latestVersion =
        versions.stream()
            .map(
                io.repsy.os.server.protocols.golang.shared.go_module.dtos.GoModuleVersionListItem
                    ::getVersion)
            .max(io.repsy.protocols.golang.shared.utils.GoVersionUtils.COMPARATOR)
            .orElse(null);

    return io.repsy.os.generated.model.GoModuleInfo.builder()
        .id(goModule.getId())
        .modulePath(goModule.getModulePath())
        .latestVersion(latestVersion)
        .createdAt(
            goModule.getCreatedAt() != null
                ? goModule.getCreatedAt().atOffset(java.time.ZoneOffset.UTC)
                : null)
        .versions(versions.stream().map(this::toVersionDto).toList())
        .build();
  }
}
