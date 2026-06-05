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
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexDto;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexEntryDto;
import io.repsy.protocols.helm.shared.utils.HelmConstants;
import io.repsy.protocols.shared.repo.dtos.Permission;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Handles GET /{repo}/index.yaml — generates and returns the Helm chart index. */
@NullMarked
public abstract class AbstractHelmIndexPullProtocolMethodHandler<ID>
    implements ProtocolMethodHandler {

  private static final Pattern INDEX_PATTERN = Pattern.compile("^/index\\.yaml$");

  private final PathParser basePathParser;
  protected final HelmFacade<ID> helmFacade;

  public AbstractHelmIndexPullProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    this.basePathParser = basePathParser;
    this.helmFacade = helmFacade;
    provider.registerMethodHandler(this);
  }

  @Override
  public List<HttpMethod> getSupportedMethods() {
    return List.of(HttpMethod.GET);
  }

  @Override
  public Map<String, Object> getProperties() {
    return Map.of("permission", Permission.READ);
  }

  @Override
  public PathParser getPathParser() {
    return request -> {
      if (!HttpMethod.GET.equals(HttpMethod.valueOf(request.getMethod()))) {
        return Optional.empty();
      }

      final var parsedPathOpt =
          AbstractHelmIndexPullProtocolMethodHandler.this.basePathParser.parse(request);
      if (parsedPathOpt.isEmpty()) {
        return Optional.empty();
      }

      final var relativePath = ProtocolContextUtils.getRelativePath(parsedPathOpt.get()).getPath();

      if (!INDEX_PATTERN.matcher(relativePath).matches()) {
        return Optional.empty();
      }

      return parsedPathOpt;
    };
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var indexDto = this.helmFacade.generateIndex(context);
    final var yamlBody = serializeToYaml(indexDto);

    return ResponseEntity.ok()
        .header("Content-Type", HelmConstants.CONTENT_TYPE_YAML)
        .body(yamlBody);
  }

  protected static String serializeToYaml(final HelmIndexDto dto) {
    final var options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setPrettyFlow(false);
    final var yaml = new Yaml(options);

    final Map<String, Object> root = new LinkedHashMap<>();
    root.put("apiVersion", dto.getApiVersion());

    final Map<String, List<Map<String, Object>>> entries = new LinkedHashMap<>();
    dto.getEntries()
        .forEach(
            (name, chartList) -> {
              final var serializedEntries = new ArrayList<Map<String, Object>>();
              for (final var entry : chartList) {
                serializedEntries.add(entryToMap(entry));
              }
              entries.put(name, serializedEntries);
            });

    root.put("entries", entries);
    root.put("generated", dto.getGenerated());

    return yaml.dump(root);
  }

  private static Map<String, Object> entryToMap(final HelmIndexEntryDto entry) {
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", entry.getName());
    map.put("version", entry.getVersion());
    if (entry.getDescription() != null) {
      map.put("description", entry.getDescription());
    }
    if (entry.getAppVersion() != null) {
      map.put("appVersion", entry.getAppVersion());
    }
    if (entry.getType() != null) {
      map.put("type", entry.getType());
    }
    map.put("digest", entry.getDigest());
    map.put("urls", entry.getUrls());
    map.put("created", entry.getCreated());
    return map;
  }
}
