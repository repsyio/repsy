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
package io.repsy.os.server.protocols.pypi.shared.python_package.mappers;

import io.repsy.os.generated.model.ReleaseDetail;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseClassifierInfo;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseListItem;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseProjectUrlInfo;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.Release;
import io.repsy.protocols.pypi.shared.python_package.dtos.PypiPackageListItem;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Function;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface PypiPackageMapper {

  default Instant map(final LocalDateTime localDateTime) {
    return localDateTime.toInstant(ZoneOffset.UTC);
  }

  default <S, T> List<T> mapList(final List<S> source, final Function<S, T> mapper) {
    return source.stream().map(mapper).toList();
  }

  io.repsy.os.generated.model.ReleaseClassifierInfo toReleaseClassifierInfoDto(
      ReleaseClassifierInfo source);

  io.repsy.os.generated.model.ReleaseProjectUrlInfo toReleaseProjectUrlInfoDto(
      ReleaseProjectUrlInfo source);

  default ReleaseDetail toReleaseDetail(
      final Release release,
      final List<ReleaseClassifierInfo> classifiers,
      final List<ReleaseProjectUrlInfo> projectUrls) {
    return ReleaseDetail.builder()
        .id(release.getId())
        .version(release.getVersion())
        .finalRelease(release.isFinalRelease())
        .preRelease(release.isPreRelease())
        .postRelease(release.isPostRelease())
        .devRelease(release.isDevRelease())
        .requiresPython(release.getRequiresPython())
        .summary(release.getSummary())
        .homePage(release.getHomePage())
        .author(release.getAuthor())
        .authorEmail(release.getAuthorEmail())
        .license(release.getLicense())
        .description(release.getDescription())
        .descriptionContentType(release.getDescriptionContentType())
        .createdAt(release.getCreatedAt())
        .classifiers(this.mapList(classifiers, this::toReleaseClassifierInfoDto))
        .projectUrls(this.mapList(projectUrls, this::toReleaseProjectUrlInfoDto))
        .build();
  }

  @Mapping(target = "updatedAt", source = "updatedAt")
  io.repsy.os.generated.model.PypiPackageListItem toPackageListItemDto(PypiPackageListItem source);

  @Mapping(target = "createdAt", source = "createdAt")
  io.repsy.os.generated.model.ReleaseListItem toReleaseListItemDto(ReleaseListItem source);
}
