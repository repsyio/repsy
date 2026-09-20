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

import static java.nio.charset.StandardCharsets.UTF_8;

import io.repsy.core.error_handling.exceptions.BadRequestException;
import io.repsy.protocols.ruby.shared.gem.dtos.GemDependency;
import io.repsy.protocols.ruby.shared.gem.dtos.GemMetadata;
import io.repsy.protocols.shared.utils.BoundedEntryReader;
import io.repsy.protocols.shared.utils.EntryTooLargeException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import lombok.experimental.UtilityClass;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.composer.Composer;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.parser.Parser;
import org.yaml.snakeyaml.parser.ParserImpl;
import org.yaml.snakeyaml.reader.StreamReader;
import org.yaml.snakeyaml.resolver.Resolver;

/**
 * Parses metadata.gz from a .gem tar archive without extracting to disk.
 *
 * <p>.gem format: outer POSIX tar containing metadata.gz (gzipped YAML gemspec) and data.tar.gz.
 */
@UtilityClass
@NullMarked
public class GemspecParser {

  private static final String METADATA_ENTRY = "metadata.gz";

  /** The largest compressed {@code metadata.gz} a gem may carry; real ones are a few KiB. */
  static final long MAX_METADATA_GZ_BYTES = 10L * 1024 * 1024;

  private static final String RUNTIME_DEP = "runtime";
  private static final String DEFAULT_PLATFORM = "ruby";
  private static final String RUBY_TAG_PREFIX = "!ruby/";

  /**
   * Reads the metadata of a gem from its stream, which is read once and closed. Only the {@code
   * metadata.gz} entry is buffered, at most {@link #MAX_METADATA_GZ_BYTES} of it; the rest of the
   * gem is skipped over, so a gem of any size is read in constant memory.
   */
  public static GemMetadata parse(final InputStream gem) {
    try (final var tar = new TarArchiveInputStream(gem)) {
      var entry = tar.getNextEntry();
      while (entry != null) {
        if (!entry.isDirectory() && METADATA_ENTRY.equals(entry.getName())) {
          return parseMetadataGz(readMetadataGz(tar, entry));
        }
        entry = tar.getNextEntry();
      }
    } catch (final IOException e) {
      throw new BadRequestException("invalidGemFile");
    }
    throw new BadRequestException("invalidGemFile");
  }

  private static byte[] readMetadataGz(final InputStream tar, final TarArchiveEntry entry)
      throws IOException {
    try {
      return BoundedEntryReader.readAllBytes(tar, entry.getSize(), MAX_METADATA_GZ_BYTES);
    } catch (final EntryTooLargeException e) {
      throw new BadRequestException("gemMetadataTooLarge");
    }
  }

  private static GemMetadata parseMetadataGz(final byte[] gzBytes) {
    final @Nullable Map<String, Object> spec;
    try (final var gzip = new GZIPInputStream(new ByteArrayInputStream(gzBytes))) {
      spec = loadGemspec(gzip);
    } catch (final IOException | RuntimeException e) {
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

      if (!(depName instanceof final String name)) {
        continue;
      }

      final var typeStr = depType instanceof final String s ? normalizeType(s) : RUNTIME_DEP;
      final var reqStr = formatDepRequirement(depReqs);

      result.add(GemDependency.builder().name(name).requirements(reqStr).type(typeStr).build());
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

  /**
   * Reads the gemspec YAML into plain maps, lists and scalars through a {@link SafeConstructor}.
   * The Ruby-specific local tags of a gemspec (<code>!ruby/object:Gem::Specification</code>, <code>
   * !ruby/object:Gem::Version</code>, ...) are turned into plain maps by {@link RubyTagComposer}
   * before the constructor sees them. Any other tag SafeConstructor does not know, such as a global
   * tag (<code>!!javax.script.ScriptEngineManager</code>), is still rejected.
   */
  @Nullable
  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadGemspec(final InputStream in) {
    final var options = new LoaderOptions();
    final var constructor = new SafeConstructor(options);
    final var parser = new ParserImpl(new StreamReader(new InputStreamReader(in, UTF_8)), options);
    constructor.setComposer(new RubyTagComposer(parser, options));
    return (Map<String, Object>) constructor.getSingleData(Object.class);
  }

  /** Composes the node tree, then turns every <code>!ruby/...</code> mapping into a plain map. */
  private static final class RubyTagComposer extends Composer {

    RubyTagComposer(final Parser parser, final LoaderOptions options) {
      super(parser, new Resolver(), options);
    }

    @Override
    @Nullable
    public Node getSingleNode() {
      final var root = super.getSingleNode();
      if (root != null) {
        retagRubyMappings(root, Collections.newSetFromMap(new IdentityHashMap<>()));
      }
      return root;
    }

    private static void retagRubyMappings(final Node node, final Set<Node> visited) {
      if (!visited.add(node)) {
        return;
      }
      if (node instanceof final MappingNode mapping) {
        if (mapping.getTag().getValue().startsWith(RUBY_TAG_PREFIX)) {
          mapping.setTag(Tag.MAP);
        }
        for (final var tuple : mapping.getValue()) {
          retagRubyMappings(tuple.getKeyNode(), visited);
          retagRubyMappings(tuple.getValueNode(), visited);
        }
      } else if (node instanceof final SequenceNode sequence) {
        sequence.getValue().forEach(child -> retagRubyMappings(child, visited));
      }
    }
  }
}
