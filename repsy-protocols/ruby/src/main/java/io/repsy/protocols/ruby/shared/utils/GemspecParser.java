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
package io.repsy.protocols.ruby.shared.utils;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.ruby.shared.gem.dtos.GemDependency;
import io.repsy.protocols.ruby.shared.gem.dtos.GemMetadata;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import lombok.experimental.UtilityClass;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * Parses metadata.gz from a .gem tar archive without extracting to disk.
 *
 * <p>.gem format: outer POSIX tar containing metadata.gz (gzipped YAML gemspec) and data.tar.gz.
 */
@UtilityClass
@NullMarked
public class GemspecParser {

  private static final String METADATA_ENTRY = "metadata.gz";
  private static final String RUNTIME_DEP = "runtime";
  private static final String DEFAULT_PLATFORM = "ruby";

  public static GemMetadata parse(final byte[] gemBytes) {
    try (final var tar = new TarArchiveInputStream(new ByteArrayInputStream(gemBytes))) {
      var entry = tar.getNextEntry();
      while (entry != null) {
        if (!entry.isDirectory() && METADATA_ENTRY.equals(entry.getName())) {
          return parseMetadataGz(tar.readAllBytes());
        }
        entry = tar.getNextEntry();
      }
    } catch (final IOException e) {
      throw new BadRequestException("invalidGemFile");
    }
    throw new BadRequestException("invalidGemFile");
  }

  @SuppressWarnings("unchecked")
  private static GemMetadata parseMetadataGz(final byte[] gzBytes) {
    final Map<String, Object> spec;
    try (final var gzip = new GZIPInputStream(new ByteArrayInputStream(gzBytes))) {
      final var opts = new LoaderOptions();
      final var constructor =
          new Constructor(opts) {
            {
              this.yamlConstructors.put(null, this.yamlConstructors.get(Tag.MAP));
            }
          };
      spec = new Yaml(constructor).load(gzip);
    } catch (final IOException e) {
      throw new BadRequestException("invalidGemFile");
    }

    if (spec == null) {
      throw new BadRequestException("invalidGemFile");
    }

    final var name = requireGemField(extractString(spec, "name"), "gemNameMissing");
    final var version = requireGemField(extractVersion(spec), "gemVersionMissing");

    final var platform = extractPlatform(spec);
    final var deps = extractDependencies(spec);

    return GemMetadata.builder()
        .name(name)
        .version(version)
        .platform(platform)
        .description(extractString(spec, "description"))
        .authors(extractAuthors(spec))
        .homepage(extractString(spec, "homepage"))
        .requiredRubyVersion(extractRequiredRubyVersion(spec))
        .runtimeDependencies(deps.stream().filter(d -> RUNTIME_DEP.equals(d.getType())).toList())
        .developmentDependencies(
            deps.stream().filter(d -> !RUNTIME_DEP.equals(d.getType())).toList())
        .build();
  }

  private static String requireGemField(final @Nullable String value, final String errorKey) {
    if (value == null || value.isBlank()) {
      throw new BadRequestException(errorKey);
    }
    return value;
  }

  @Nullable
  private static String extractString(final Map<String, Object> spec, final String key) {
    final var value = spec.get(key);
    return value instanceof final String s ? s.trim() : null;
  }

  @Nullable
  private static String extractVersion(final Map<String, Object> spec) {
    final var versionObj = spec.get("version");
    if (versionObj instanceof final Map<?, ?> versionMap) {
      final var v = versionMap.get("version");
      return v instanceof final String s ? s.trim() : null;
    }
    return versionObj instanceof final String s ? s.trim() : null;
  }

  private static String extractPlatform(final Map<String, Object> spec) {
    final var platform = extractString(spec, "platform");
    return (platform == null || platform.isBlank()) ? DEFAULT_PLATFORM : platform;
  }

  @Nullable
  private static String extractAuthors(final Map<String, Object> spec) {
    final var authors = spec.get("authors");
    if (authors instanceof final List<?> list) {
      return list.stream()
          .filter(String.class::isInstance)
          .map(String.class::cast)
          .reduce((a, b) -> a + ", " + b)
          .orElse(null);
    }
    return authors instanceof final String s ? s : null;
  }

  @Nullable
  private static String extractRequiredRubyVersion(final Map<String, Object> spec) {
    final var req = spec.get("required_ruby_version");
    if (req instanceof final Map<?, ?> reqMap) {
      final var reqs = reqMap.get("requirements");
      if (reqs instanceof final List<?> list && !list.isEmpty()) {
        return list.stream()
            .filter(List.class::isInstance)
            .map(r -> formatRequirement((List<?>) r))
            .reduce((a, b) -> a + ", " + b)
            .orElse(null);
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private static List<GemDependency> extractDependencies(final Map<String, Object> spec) {
    final var deps = spec.get("dependencies");
    if (!(deps instanceof final List<?> depList)) {
      return List.of();
    }

    final var result = new ArrayList<GemDependency>(depList.size());
    for (final var dep : depList) {
      if (!(dep instanceof final Map<?, ?> depMap)) {
        continue;
      }
      final var depName = depMap.get("name");
      final var depType = depMap.get("type");
      final var depReqs = depMap.get("requirement");

      if (!(depName instanceof String)) {
        continue;
      }

      final var typeStr = depType instanceof final String s ? normalizeType(s) : RUNTIME_DEP;
      final var reqStr = formatDepRequirement(depReqs);

      result.add(
          GemDependency.builder()
              .name((String) depName)
              .requirements(reqStr)
              .type(typeStr)
              .build());
    }
    return List.copyOf(result);
  }

  private static String normalizeType(final String type) {
    return type.startsWith(":") ? type.substring(1) : type;
  }

  private static String formatDepRequirement(final @Nullable Object req) {
    if (!(req instanceof final Map<?, ?> reqMap)) {
      return ">= 0";
    }
    final var reqs = reqMap.get("requirements");
    if (!(reqs instanceof final List<?> list)) {
      return ">= 0";
    }
    return list.stream()
        .filter(List.class::isInstance)
        .map(r -> formatRequirement((List<?>) r))
        .reduce((a, b) -> a + ", " + b)
        .orElse(">= 0");
  }

  private static String formatRequirement(final List<?> pair) {
    if (pair.size() < 2) {
      return ">= 0";
    }
    final var op = pair.get(0);
    final var ver = pair.get(1);
    final var verStr = ver instanceof final Map<?, ?> m ? m.get("version") : ver;
    return op + " " + verStr;
  }
}
