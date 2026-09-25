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
package io.repsy.os.server.protocols.npm.shared.npm_package.repositories;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.protocols.npm.shared.search.NpmSearchQuery;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("NpmSearchCandidateRepository.build")
class NpmSearchCandidateRepositoryTest {

  private static NpmSearchCandidateRepository.Built build(final String text) {
    return NpmSearchCandidateRepository.build(NpmSearchQuery.parse(text, null, null));
  }

  @Test
  @DisplayName("a query without terms has no filter and no ranking, only the order by name")
  void noTerms() {
    final var built = build("");

    assertThat(built.where()).isEmpty();
    assertThat(built.order()).isEmpty();
    assertThat(built.parameters()).isEmpty();
  }

  @Test
  @DisplayName("each term filters on the key, the description and a keyword, and ranks the name")
  void terms() {
    final var built = build("left pad");

    assertThat(built.parameters())
        .containsEntry("c0", "%left%")
        .containsEntry("s0", "left%")
        .containsEntry("e0", "left")
        .containsEntry("c1", "%pad%")
        .containsEntry("e1", "pad");
    assertThat(built.where())
        .contains("like :c0 escape '!'")
        .contains("lower(pv.description) like :c1 escape '!'")
        .contains("exists (select k.id from PackageKeyword k")
        .contains("lower(k.keyword) like :c1 escape '!'");
    assertThat(built.order()).startsWith("(case when lower(p.name) = :e0").endsWith("0) desc, ");
    assertThat(built.order()).contains("like :s1 escape '!'");
  }

  @Test
  @DisplayName("takes %, _ and ! in a term literally, and the @ of a scoped term away")
  void escapes() {
    final var built = build("@acme/a_b%c!d");

    assertThat(built.parameters())
        .containsEntry("c0", "%acme/a!_b!%c!!d%")
        .containsEntry("e0", "acme/a_b%c!d");
  }

  @Test
  @DisplayName("scope and keywords are filters of their own")
  void scopeAndKeywords() {
    final var built = build("scope:@Acme keywords:Pad,ui");

    assertThat(built.where())
        .contains("lower(p.scope) = :scope")
        .contains("lower(k.keyword) in :keywords");
    assertThat(built.parameters()).containsEntry("scope", "acme");
    assertThat(built.parameters().get("keywords")).asList().containsExactlyInAnyOrder("pad", "ui");
    assertThat(built.order()).isEmpty();
  }

  @Test
  @DisplayName("author: and maintainer: filter on the name or the email, in any case (RPS-1343)")
  void people() {
    final var built = build("author:Ann,bob@x.io maintainer:Carol");

    assertThat(built.where())
        .contains("lower(pv.authorName) in :authors")
        .contains("lower(pv.authorEmail) in :authors")
        .contains("exists (select m.id from PackageMaintainer m where m.packageVersion = pv")
        .contains("lower(m.name) in :maintainers")
        .contains("lower(m.email) in :maintainers");
    assertThat(built.parameters().get("authors"))
        .asList()
        .containsExactlyInAnyOrder("ann", "bob@x.io");
    assertThat(built.parameters().get("maintainers")).asList().containsExactly("carol");
    assertThat(built.order()).isEmpty();
  }

  @Test
  @DisplayName("is: and not: add a condition of their own, and no parameter (RPS-1343)")
  void flags() {
    final var is = build("is:deprecated is:unstable is:insecure");
    final var not = build("not:deprecated not:unstable not:insecure");

    assertThat(is.where())
        .contains("coalesce(pv.deprecated, false) = true")
        .contains("(pv.version like '0.%' or pv.version like '1.0.0-%')")
        .contains("exists (select f.id from VulnerabilityFinding f join f.scan s")
        .contains("f.packageName = case when p.scope is null then p.name")
        .contains("f.packageVersion = p.latest")
        .contains("NOT_AFFECTED");
    assertThat(not.where())
        .contains("coalesce(pv.deprecated, false) = false")
        .contains("not (pv.version like '0.%' or pv.version like '1.0.0-%')")
        .contains("not exists (select f.id from VulnerabilityFinding f");
    assertThat(is.parameters()).isEmpty();
    assertThat(not.parameters()).isEmpty();
  }

  @Test
  @DisplayName("a query that matches nothing says so in the condition (RPS-1343)")
  void matchesNothing() {
    assertThat(build("is:shiny").where()).endsWith(" and 1 = 0");
    assertThat(build("is:shiny pad").where()).doesNotContain("1 = 0");
    assertThat(build("").where()).doesNotContain("1 = 0");
  }

  @Test
  @DisplayName("boost-exact:false ranks a whole-name match as a prefix, not above it")
  void boostExact() {
    assertThat(build("pad").order()).contains(" then 2 when lower(p.name)");
    assertThat(build("pad boost-exact:false").order())
        .doesNotContain(" then 2 when")
        .contains(" then 1 when lower(p.name)");
  }
}
