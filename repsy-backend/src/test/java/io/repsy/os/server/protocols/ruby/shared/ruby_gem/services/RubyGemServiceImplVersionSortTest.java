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
package io.repsy.os.server.protocols.ruby.shared.ruby_gem.services;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.repsy.os.generated.model.GemVersionListItem;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.mappers.RubyGemConverter;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemDependencyRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemRepository;
import io.repsy.os.server.protocols.ruby.shared.ruby_gem.repositories.RubyGemVersionRepository;
import io.repsy.os.shared.repo.repositories.RepoRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/**
 * RPS-1688: {@code findAllVersions} whitelists a {@code version} sort but used to pass it straight
 * through to a JPA {@code ORDER BY}, which sorts the column as a string ({@code "10.0.0"} above
 * {@code "9.0.0"}). A mocked repository returns the versions in an order neither a plain string
 * sort nor insertion order would produce, and the page must still come back correct.
 */
@DisplayName("RubyGemServiceImpl.findAllVersions version sort (RPS-1688)")
class RubyGemServiceImplVersionSortTest {

  private final RubyGemRepository gemRepository = mock(RubyGemRepository.class);
  private final RubyGemVersionRepository versionRepository = mock(RubyGemVersionRepository.class);
  private final RubyGemDependencyRepository dependencyRepository =
      mock(RubyGemDependencyRepository.class);
  private final RepoRepository repoRepository = mock(RepoRepository.class);
  private final RubyGemConverter converter = mock(RubyGemConverter.class);

  private final RubyGemServiceImpl service =
      new RubyGemServiceImpl(
          this.gemRepository,
          this.versionRepository,
          this.dependencyRepository,
          this.repoRepository,
          this.converter);

  @Test
  @DisplayName("sorts a version-descending request by precedence, not by string or insertion order")
  void sortsByVersionPrecedenceDescending() {

    final var gemId = UUID.randomUUID();

    // Neither insertion order nor a plain string sort ("9.0.0" > "10.0.0" > "1.2.0" as text)
    // matches the correct numeric-precedence order asserted below.
    final var rows = List.of(projection("9.0.0"), projection("10.0.0"), projection("1.2.0"));

    when(this.versionRepository.findAllByGemId(eq(gemId), any())).thenReturn(rows);
    when(this.converter.toGemVersionListItemDto(any()))
        .thenAnswer(inv -> toDto(inv.getArgument(0)));

    final var pageable = PageRequest.of(0, 10, Sort.by(Sort.Direction.DESC, "version"));

    final var page = this.service.findAllVersions(gemId, "", pageable);

    assertThat(page.getContent().stream().map(GemVersionListItem::getVersion))
        .containsExactly("10.0.0", "9.0.0", "1.2.0");
  }

  private static io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemVersionListItem
      projection(final String version) {
    final var row =
        mock(io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemVersionListItem.class);
    when(row.getVersion()).thenReturn(version);
    when(row.getPlatform()).thenReturn("ruby");
    when(row.isYanked()).thenReturn(false);
    when(row.getCreatedAt()).thenReturn(Instant.EPOCH);
    return row;
  }

  private static GemVersionListItem toDto(
      final io.repsy.os.server.protocols.ruby.shared.ruby_gem.dtos.GemVersionListItem source) {
    return new GemVersionListItem()
        .version(source.getVersion())
        .platform(source.getPlatform())
        .yanked(source.isYanked());
  }
}
