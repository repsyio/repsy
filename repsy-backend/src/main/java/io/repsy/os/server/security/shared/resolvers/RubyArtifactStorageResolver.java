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
package io.repsy.os.server.security.shared.resolvers;

import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemVersionRepository;
import io.repsy.os.server.security.shared.ArtifactStorageResolver;
import io.repsy.protocols.ruby.shared.storage.services.RubyStorageService;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Component;

/**
 * The gem filename depends on {@code platform} (default {@code "ruby"}, but native-extension gems
 * publish under e.g. {@code "x86_64-linux"}/{@code "java"}) — not derivable from name+version
 * alone, so it is read from {@code ruby_gem_version} (already persisted at push time). If more than
 * one platform was ever published for the same version, any one of them is picked; there is no
 * signal in the coordinate to prefer one over another.
 */
@Component
@NullMarked
@RequiredArgsConstructor
public class RubyArtifactStorageResolver implements ArtifactStorageResolver {

  private static final Set<String> SUPPORTED_REPO_TYPES = Set.of("RUBY");

  private final @NonNull RubyGemRepository rubyGemRepository;
  private final @NonNull RubyGemVersionRepository rubyGemVersionRepository;
  private final @NonNull RubyStorageService rubyStorageService;

  @Override
  public @NonNull Optional<String> resolve(
      final @NonNull UUID repoId,
      final @NonNull String repoName,
      final @NonNull String artifactName,
      final @NonNull String artifactVersion) {

    return this.rubyGemRepository
        .findByRepoIdAndName(repoId, artifactName)
        .flatMap(
            gem ->
                this.rubyGemVersionRepository
                    .findByGemIdAndVersion(gem.getId(), artifactVersion)
                    .stream()
                    .findFirst())
        .map(
            gemVersion ->
                this.rubyStorageService.getGemRelativePath(
                    artifactName, artifactVersion, gemVersion.getPlatform()));
  }

  @Override
  public @NonNull Set<String> getSupportedRepoTypes() {
    return SUPPORTED_REPO_TYPES;
  }
}
