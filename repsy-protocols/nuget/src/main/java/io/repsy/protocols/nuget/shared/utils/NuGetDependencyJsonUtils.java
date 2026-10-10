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
package io.repsy.protocols.nuget.shared.utils;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyGroupInfo;
import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Writes and reads the stored JSON of the dependencies of a NuGet package version. */
@Slf4j
@NullMarked
@UtilityClass
public final class NuGetDependencyJsonUtils {

  private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

  /** Every dependency of every group, each carrying the target framework of its group. */
  public static List<NuGetDependencyInfo> flatten(final List<NuGetDependencyGroupInfo> groups) {
    return groups.stream().flatMap(g -> g.dependencies().stream()).toList();
  }

  /** The flat, legacy shape of the stored dependencies: {@code [{"packageId":..,...}]}. */
  public static String toDependenciesJson(final List<NuGetDependencyInfo> dependencies) {
    return OBJECT_MAPPER.writeValueAsString(dependencies);
  }

  /**
   * The value to store for the dependency groups of a package version. When every group has at
   * least one dependency the flat list of {@link #toDependenciesJson} says everything (each
   * dependency names its target framework), and that is what is stored, so those rows stay as they
   * always were and readers that only know the flat list keep working. Only a group without
   * dependencies needs the grouped shape {@code {"groups":[{"targetFramework":..,
   * "dependencies":[..]}]}}, which {@link #parseDependencyGroupsJson} reads next to the flat one
   * (RPS-1555).
   */
  public static String toDependencyGroupsJson(final List<NuGetDependencyGroupInfo> groups) {

    if (groups.stream().noneMatch(g -> g.dependencies().isEmpty())) {
      return toDependenciesJson(flatten(groups));
    }
    return OBJECT_MAPPER.writeValueAsString(
        new StoredGroups(groups.stream().map(StoredGroup::of).toList()));
  }

  /** Same as {@link #parseDependencyGroupsJson}, flattened. */
  public static List<NuGetDependencyInfo> parseDependenciesJson(
      @Nullable final String json, final String packageId, final String version) {

    return flatten(parseDependencyGroupsJson(json, packageId, version));
  }

  /**
   * Reads the dependency groups stored for a package version, from the flat list of earlier
   * versions (grouped by target framework, in order of first appearance) or from the grouped shape.
   * A {@code null} or blank value means the package declares none. A value that is there but cannot
   * be read is a stored-data problem, not an absence of dependencies, so it is logged at {@code
   * warn} with the package id and version. The value itself is left out of the log, and the caller
   * still gets an empty list so one bad column does not fail the rest of the version.
   */
  public static List<NuGetDependencyGroupInfo> parseDependencyGroupsJson(
      @Nullable final String json, final String packageId, final String version) {

    if (json == null || json.isBlank()) {
      return List.of();
    }
    try {
      return readDependencyGroups(json);
    } catch (final Exception e) {
      log.warn(
          "Ignoring unreadable dependencies of NuGet package {} {} ({} characters): {}",
          packageId,
          version,
          json.length(),
          e.getClass().getSimpleName());
      return List.of();
    }
  }

  private static List<NuGetDependencyGroupInfo> readDependencyGroups(final String json) {

    final var node = OBJECT_MAPPER.readTree(json);

    if (node.isArray()) {
      final List<NuGetDependencyInfo> flat =
          OBJECT_MAPPER
              .readerFor(new TypeReference<List<NuGetDependencyInfo>>() {})
              .readValue(node);
      return groupByTargetFramework(flat);
    }
    if (node.path("groups").isArray()) {
      return OBJECT_MAPPER.treeToValue(node, StoredGroups.class).groups().stream()
          .map(StoredGroup::toInfo)
          .toList();
    }
    if (node.isNull()) {
      return List.of();
    }
    throw new IllegalArgumentException("Neither a dependency list nor dependency groups");
  }

  private static List<NuGetDependencyGroupInfo> groupByTargetFramework(
      final List<NuGetDependencyInfo> flat) {

    final var groups = new LinkedHashMap<String, List<NuGetDependencyInfo>>();
    for (final var dep : flat) {
      final var key = dep.targetFramework() != null ? dep.targetFramework() : "";
      groups.computeIfAbsent(key, k -> new ArrayList<>()).add(dep);
    }
    return groups.entrySet().stream()
        .map(
            e ->
                new NuGetDependencyGroupInfo(
                    e.getKey().isEmpty() ? null : e.getKey(), e.getValue()))
        .toList();
  }

  /** The grouped shape of the stored dependencies, see {@link #toDependencyGroupsJson}. */
  private record StoredGroups(List<StoredGroup> groups) {}

  @JsonInclude(Include.NON_NULL)
  private record StoredGroup(
      @Nullable String targetFramework, List<StoredDependency> dependencies) {

    static StoredGroup of(final NuGetDependencyGroupInfo group) {
      return new StoredGroup(
          group.targetFramework(),
          group.dependencies().stream()
              .map(d -> new StoredDependency(d.packageId(), d.versionRange()))
              .toList());
    }

    NuGetDependencyGroupInfo toInfo() {
      return new NuGetDependencyGroupInfo(
          this.targetFramework,
          this.dependencies.stream()
              .map(
                  d ->
                      new NuGetDependencyInfo(
                          d.packageId(), d.versionRange(), this.targetFramework))
              .toList());
    }
  }

  private record StoredDependency(String packageId, String versionRange) {}
}
