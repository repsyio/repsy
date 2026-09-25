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
package io.repsy.protocols.helm.shared.oci.dtos;

import io.repsy.protocols.helm.shared.chart.dtos.HelmChartForm;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NullMarked;

/**
 * What an OCI manifest push writes: the chart version the manifest describes and the manifest (the
 * tag or digest it is stored under) that points at it. Both are written together (RPS-1354), so the
 * manifest's chart id is not part of the form: it is the id of the chart version written first.
 */
@Value
@Builder
@NullMarked
public class HelmOciManifestPushForm {

  HelmChartForm chart;
  String name;
  String reference;
  String digest;
  String mediaType;
  String content;
}
