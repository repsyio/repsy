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
        .name(info.name())
        .latestVersion(info.version())
        .description(info.description())
        .type(info.type())
        .updatedAt(info.lastUpdatedAt())
        .build();
  }

  public HelmChartVersionItem toVersionItem(final HelmChartInfo info) {
    return HelmChartVersionItem.builder()
        .version(info.version())
        .appVersion(info.appVersion())
        .description(info.description())
        .type(info.type())
        .digest(info.digest())
        .size(info.size())
        .createdAt(info.createdAt())
        .build();
  }

  public HelmChartDetail toDetail(final HelmChartInfo info) {
    return HelmChartDetail.builder()
        .name(info.name())
        .version(info.version())
        .description(info.description())
        .appVersion(info.appVersion())
        .type(info.type())
        .digest(info.digest())
        .size(info.size())
        .createdAt(info.createdAt())
        .lastUpdatedAt(info.lastUpdatedAt())
        .build();
  }
}
