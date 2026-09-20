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
package io.repsy.protocols.helm.protocol.handlers.classic;

import io.repsy.libs.protocol.router.PathParser;
import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.protocol.router.ProtocolMethodHandler;
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmFacade;
import io.repsy.protocols.helm.shared.chart.dtos.HelmChartMetadata;
import io.repsy.protocols.helm.shared.utils.HelmChartParser;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import io.repsy.protocols.shared.utils.SpooledUpload;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Handles POST /{repo}/api/charts — accepts a multipart/form-data upload of a .tgz chart. */
@NullMarked
public abstract class AbstractHelmChartPushProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern CHART_PUSH_PATTERN = Pattern.compile("^/api/charts$");

  private final PathParser basePathParser;
  private final HelmFacade<ID> helmFacade;

  public AbstractHelmChartPushProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.helmFacade = helmFacade;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.POST);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.WRITE, "writeOperation", true);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.POST.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt =
          AbstractHelmChartPushProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!CHART_PUSH_PATTERN.matcher(relativePath).matches()) {
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response)
      throws Exception {

    final var chartPart = request.getPart(HelmConstants.CHART_PART_NAME);

    if (chartPart == null) {
      return ResponseEntity.badRequest().body("Missing 'chart' part");
    }

    // The chart is copied to a temporary file once, hashing it on the way, so it is never held in
    // memory: the metadata is read from the file and then the file is streamed into storage.
    try (final var chartStream = chartPart.getInputStream();
        final var chart = SpooledUpload.spool(chartStream)) {

      final HelmChartMetadata metadata;
      try (final var in = chart.openStream()) {
        metadata = HelmChartParser.parseChartYaml(in);
      }

      try (final var in = chart.openStream()) {
        this.helmFacade.pushChart(
            context,
            metadata.getName(),
            metadata.getVersion(),
            metadata.getDescription() != null ? metadata.getDescription() : "",
            metadata.getAppVersion() != null ? metadata.getAppVersion() : "",
            metadata.getType(),
            HelmConstants.SHA256_PREFIX + chart.sha256Hex(),
            in,
            chart.size());
      }
    }

    return ResponseEntity.status(HttpStatus.CREATED).build();
  }
}
