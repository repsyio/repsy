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

/**
 * A plugin whose registered goal prefix was corrected once its jar arrived: the POM of {@code mvn
 * deploy} is stored before the jar, so the prefix it registered was the one derived from the
 * artifactId, and the {@code goalPrefix} of the jar's {@code plugin.xml} replaced it (RPS-1589).
 *
 * @param artifactId the artifactId of the plugin
 * @param from the prefix that was registered
 * @param to the prefix the jar's {@code plugin.xml} names, now registered
 */
@NullMarked
public record PluginPrefixChange(String artifactId, String from, String to) {}
