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
package io.repsy.protocols.maven.shared.artifact.dtos;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * A Maven plugin as the repository has registered it: what the group-level {@code
 * maven-metadata.xml} that is answered when none is stored lists, so that {@code mvn prefix:goal}
 * finds the plugin (RPS-1438).
 *
 * @param artifactId the artifactId of the plugin
 * @param name the {@code <name>} of its POM, or {@code null} when it has none
 * @param prefix the goal prefix the plugin is addressed by
 */
@NullMarked
public record RegisteredPlugin(String artifactId, @Nullable String name, String prefix) {}
