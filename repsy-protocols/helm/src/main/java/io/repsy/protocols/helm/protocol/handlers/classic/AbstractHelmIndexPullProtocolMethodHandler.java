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
import io.repsy.protocols.helm.protocol.HelmProtocolProvider;
import io.repsy.protocols.helm.protocol.facades.HelmProtocolFacade;
import io.repsy.protocols.helm.shared.constants.HelmConstants;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexEntryInfo;
import io.repsy.protocols.helm.shared.index.dtos.HelmIndexInfo;
import io.repsy.protocols.shared.handlers.AbstractFacadeProtocolMethodHandler;
import io.repsy.protocols.shared.handlers.HandlerRoute;
import io.repsy.protocols.shared.repo.dtos.Permission;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;

/** Handles GET /{repo}/index.yaml — generates and returns the Helm chart index. */
@NullMarked
public abstract class AbstractHelmIndexPullProtocolMethodHandler<ID>
    extends AbstractFacadeProtocolMethodHandler<HelmProtocolFacade<ID>> {

  private static final Pattern INDEX_PATTERN = Pattern.compile("^/index\\.yaml$");

  public AbstractHelmIndexPullProtocolMethodHandler(
      final PathParser basePathParser,
      final HelmProtocolFacade<ID> helmFacade,
      final HelmProtocolProvider provider) {
    super(
        HandlerRoute.of(Permission.READ, HttpMethod.GET)
            .writeOperation(false)
            .path(INDEX_PATTERN.asMatchPredicate()),
        basePathParser,
        helmFacade,
        provider);
  }

  @Override
  public ResponseEntity<Object> handle(
      final ProtocolContext context,
      final HttpServletRequest request,
      final HttpServletResponse response) {

    final var indexDto = this.facade.generateIndex(context);
    final var yamlBody = serializeToYaml(indexDto);

    return ResponseEntity.ok()
        .header("Content-Type", HelmConstants.CONTENT_TYPE_YAML)
        .body(yamlBody);
  }

  protected static String serializeToYaml(final HelmIndexInfo dto) {
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

  private static void putIfPresent(
      final Map<String, Object> map, final String key, final @Nullable Object value) {
    if (value != null) {
      map.put(key, value);
    }
  }

  private static Map<String, Object> entryToMap(final HelmIndexEntryInfo entry) {
    final Map<String, Object> map = new LinkedHashMap<>();
    map.put("name", entry.getName());
    map.put("version", entry.getVersion());
    putIfPresent(map, "description", entry.getDescription());
    putIfPresent(map, "appVersion", entry.getAppVersion());
    putIfPresent(map, "type", entry.getType());
    putIfPresent(map, "apiVersion", entry.getApiVersion());
    putIfPresent(map, "dependencies", entry.getDependencies());
    map.put("digest", entry.getDigest());
    map.put("urls", entry.getUrls());
    map.put("created", entry.getCreated());
    return map;
  }
}
