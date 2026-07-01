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
package io.repsy.protocols.ruby.shared.gem.dtos;

import java.util.List;
import lombok.Builder;
import lombok.Value;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Value
@Builder
@NullMarked
public class GemMetadata {

  String name;
  String version;
  String platform;

  @Nullable String description;
  @Nullable String authors;
  @Nullable String homepage;
  @Nullable String requiredRubyVersion;

  List<GemDependency> runtimeDependencies;
  List<GemDependency> developmentDependencies;
}
