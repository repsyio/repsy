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

import io.repsy.os.server.protocols.pypi.shared.python_package.dtos.PackageInfo;
import io.repsy.os.server.protocols.pypi.shared.python_package.entities.PypiPackage;
import org.jspecify.annotations.NullMarked;
import org.springframework.core.convert.converter.Converter;
import org.springframework.core.convert.support.GenericConversionService;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class PackageToPackageInfoConverter implements Converter<PypiPackage, PackageInfo> {
  PackageToPackageInfoConverter(final GenericConversionService conversionService) {
    conversionService.addConverter(this);
  }

  @Override
  public PackageInfo convert(final PypiPackage source) {
    return PackageInfo.builder()
        .id(source.getId())
        .name(source.getName())
        .normalizedName(source.getNormalizedName())
        .latestVersion(source.getLatestVersion())
        .stableVersion(source.getStableVersion())
        .repoName(source.getRepo().getName())
        .repoId(source.getRepo().getId())
        .createdAt(source.getCreatedAt())
        .build();
  }
}
