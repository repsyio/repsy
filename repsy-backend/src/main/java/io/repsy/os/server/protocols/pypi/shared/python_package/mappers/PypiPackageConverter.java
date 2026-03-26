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

import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseClassifierInfo;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseDetail;
import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.ReleaseProjectURLInfo;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.Release;
import java.util.List;
import org.jspecify.annotations.NullMarked;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
@NullMarked
public interface PypiPackageConverter {
  default ReleaseDetail toReleaseDetail(
      final Release release,
      final List<ReleaseClassifierInfo> classifiers,
      final List<ReleaseProjectURLInfo> projectURLs) {
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
        .classifiers(classifiers)
        .projectUrls(projectURLs)
        .build();
  }
}
