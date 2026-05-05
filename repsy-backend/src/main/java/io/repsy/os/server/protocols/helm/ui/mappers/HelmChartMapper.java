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
package io.repsy.os.server.protocols.helm.ui.mappers;

import io.repsy.os.generated.model.HelmChartDetail;
import io.repsy.os.generated.model.HelmChartListItem;
import io.repsy.os.generated.model.HelmChartVersionItem;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartInfo;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

@Component
@NullMarked
public class HelmChartMapper {

  public HelmChartListItem toListItem(final HelmChartInfo info) {
    return HelmChartListItem.builder()
        .name(info.getName())
        .latestVersion(info.getVersion())
        .description(info.getDescription())
        .type(info.getType())
        .updatedAt(info.getLastUpdatedAt())
        .build();
  }

  public HelmChartVersionItem toVersionItem(final HelmChartInfo info) {
    return HelmChartVersionItem.builder()
        .version(info.getVersion())
        .appVersion(info.getAppVersion())
        .description(info.getDescription())
        .type(info.getType())
        .digest(info.getDigest())
        .size(info.getSize())
        .createdAt(info.getCreatedAt())
        .build();
  }

  public HelmChartDetail toDetail(final HelmChartInfo info) {
    return HelmChartDetail.builder()
        .name(info.getName())
        .version(info.getVersion())
        .description(info.getDescription())
        .appVersion(info.getAppVersion())
        .type(info.getType())
        .digest(info.getDigest())
        .size(info.getSize())
        .createdAt(info.getCreatedAt())
        .lastUpdatedAt(info.getLastUpdatedAt())
        .build();
  }
}
