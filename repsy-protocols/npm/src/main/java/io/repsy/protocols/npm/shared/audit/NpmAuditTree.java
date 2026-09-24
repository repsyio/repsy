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
package io.repsy.protocols.npm.shared.audit;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The dependency tree a legacy audit request ({@code /audits} and {@code /audits/quick}) carries:
 * {@code dependencies.<name>} holds {@code version}, {@code dev}, {@code optional}, {@code bundled}
 * and, one level down, its own {@code dependencies}. The tree is walked without recursion, so a
 * deep tree cannot overflow the stack, and it is refused beyond {@value #MAX_DEPTH} levels.
 *
 * @param versionsByName The distinct versions of each package in the tree
 * @param nodes Every place a package version occurs in the tree
 */
@NullMarked
public record NpmAuditTree(Map<String, Set<String>> versionsByName, List<Node> nodes) {

  static final int MAX_DEPTH = 1000;
  static final int MAX_NODES = 250_000;

  private static final String DEPENDENCIES = "dependencies";
  private static final String PATH_SEPARATOR = ">";

  /** A name in the tree that knows the name it hangs from, so that no path is built until asked. */
  record Segment(String name, @Nullable Segment parent) {

    String path() {
      final var names = new ArrayDeque<String>();

      for (var segment = this; segment != null; segment = segment.parent()) {
        names.push(segment.name());
      }

      return String.join(PATH_SEPARATOR, names);
    }
  }

  /** One occurrence of a package version. */
  public record Node(
      String name,
      String version,
      boolean dev,
      boolean optional,
      boolean bundled,
      Segment segment) {

    /** The names from the top of the tree to the package, joined with {@code >}. */
    public String path() {
      return this.segment.path();
    }
  }

  private record Frame(JsonNode dependencies, @Nullable Segment parent, int depth) {}

  /**
   * Walks the tree of a legacy audit request.
   *
   * @throws InvalidAuditRequestException If the tree is nested deeper than {@value #MAX_DEPTH}
   */
  public static NpmAuditTree parse(final JsonNode root) {
    final var walker = new Walker();

    walker.pending.push(new Frame(root.path(DEPENDENCIES), null, 1));

    while (!walker.pending.isEmpty()) {
      walker.visit(walker.pending.pop());
    }

    return new NpmAuditTree(walker.versionsByName, walker.nodes);
  }

  /** The state of one walk: what has been found and what is still to be visited. */
  private static final class Walker {

    private final Map<String, Set<String>> versionsByName = new LinkedHashMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private final ArrayDeque<Frame> pending = new ArrayDeque<>();

    void visit(final Frame frame) {
      if (frame.depth() > MAX_DEPTH) {
        throw new InvalidAuditRequestException("the dependency tree is nested too deeply");
      }

      if (!frame.dependencies().isObject()) {
        return;
      }

      final var children = new ArrayList<Frame>();

      for (final var entry : frame.dependencies().properties()) {
        if (entry.getValue().isObject()) {
          children.add(this.visitPackage(frame, entry.getKey(), entry.getValue()));
        }
      }

      // Pushed in reverse, so that the walk visits the children in the order of the request.
      for (final var child : children.reversed()) {
        this.pending.push(child);
      }
    }

    private Frame visitPackage(final Frame frame, final String name, final JsonNode node) {
      final var segment = new Segment(name, frame.parent());
      final var version = node.path("version");

      if (version.isString()) {
        this.record(
            new Node(
                name,
                version.asString(),
                node.path("dev").asBoolean(false),
                node.path("optional").asBoolean(false),
                node.path("bundled").asBoolean(false),
                segment));
      }

      return new Frame(node.path(DEPENDENCIES), segment, frame.depth() + 1);
    }

    private void record(final Node node) {
      if (this.nodes.size() >= MAX_NODES) {
        throw new InvalidAuditRequestException("the dependency tree has too many packages");
      }

      this.nodes.add(node);
      this.versionsByName
          .computeIfAbsent(node.name(), _ -> new LinkedHashSet<>())
          .add(node.version());
    }
  }

  /** How many places hold a package that is not a dev dependency. */
  public long dependencies() {
    return this.nodes.stream().filter(node -> !node.dev()).count();
  }

  /** How many places hold a dev dependency. */
  public long devDependencies() {
    return this.nodes.stream().filter(Node::dev).count();
  }

  /** How many places hold an optional dependency. */
  public long optionalDependencies() {
    return this.nodes.stream().filter(Node::optional).count();
  }

  /** How many places hold a package at all. */
  public long totalDependencies() {
    return this.nodes.size();
  }
}
