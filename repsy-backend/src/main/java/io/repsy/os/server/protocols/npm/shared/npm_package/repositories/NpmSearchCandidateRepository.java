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

import io.repsy.os.server.protocols.npm.shared.npm_package.dtos.NpmSearchCandidate;
import io.repsy.protocols.npm.shared.search.NpmSearchQuery;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;
import org.springframework.stereotype.Repository;

/**
 * Finds the packages a search matches. The query is built for the terms of the search, because a
 * search has up to five terms and an optional scope and keyword filter, and a fixed query would
 * have to say "no term" with null parameters. Every filter is in SQL, so the count of the matches
 * is true, and a cap on the rows the search loads takes the best matches: whole-name matches first,
 * then prefixes, then the rest by name.
 */
@Repository
@NullMarked
public class NpmSearchCandidateRepository {

  private static final String FROM =
      """
      from NpmPackage p join p.packageVersions pv
      where p.repo.id = :repoId and pv.version = p.latest""";

  private static final String KEY =
      "lower(case when p.scope is null then p.name else concat(p.scope, '/', p.name) end)";

  private static final String LIKE = " like :%s escape '!'";

  @PersistenceContext private EntityManager entityManager;

  /** The generated JPQL and the parameters that go with it, for a search. */
  record Built(
      String where,
      String order,
      Map<String, Object> filterParameters,
      Map<String, Object> rankParameters) {

    /** Every parameter, for the query that filters and ranks. */
    Map<String, Object> parameters() {
      final var all = new HashMap<>(this.filterParameters);
      all.putAll(this.rankParameters);
      return all;
    }
  }

  /**
   * The latest versions that match the query, best first, at most {@code limit} of them. The best
   * are the packages whose name is a term, then those whose name starts with one, then the rest,
   * each by name.
   */
  public List<NpmSearchCandidate> find(
      final UUID repoId, final NpmSearchQuery query, final int limit) {

    final var built = build(query);
    final var jpql =
        "select new io.repsy.os.server.protocols.npm.shared.npm_package.dtos.NpmSearchCandidate("
            + "pv.id, p.scope, p.name, p.latest, pv.description, pv.createdAt, pv.authorName,"
            + " pv.authorEmail, pv.authorUrl, pv.homepage, pv.repositoryUrl, pv.bugsUrl) "
            + FROM
            + built.where()
            + " order by "
            + built.order()
            + "p.name, p.scope";

    final var typed = this.entityManager.createQuery(jpql, NpmSearchCandidate.class);
    typed.setParameter("repoId", repoId);
    built.parameters().forEach(typed::setParameter);

    return typed.setMaxResults(limit).getResultList();
  }

  /** How many packages match the query, whatever the cap of {@link #find} is. */
  public long count(final UUID repoId, final NpmSearchQuery query) {
    final var built = build(query);
    final var typed =
        this.entityManager.createQuery("select count(p.id) " + FROM + built.where(), Long.class);
    typed.setParameter("repoId", repoId);
    built.filterParameters().forEach(typed::setParameter);

    return typed.getSingleResult();
  }

  static Built build(final NpmSearchQuery query) {
    final var where = new StringBuilder();
    final var order = new StringBuilder();
    final var parameters = new HashMap<String, Object>();
    final var rankParameters = new HashMap<String, Object>();

    if (query.scope() != null) {
      where.append(" and lower(p.scope) = :scope");
      parameters.put("scope", query.scope().toLowerCase(Locale.ROOT));
    }

    if (!query.keywords().isEmpty()) {
      where.append(" and ").append(keywordExists("lower(k.keyword) in :keywords"));
      parameters.put("keywords", List.copyOf(query.keywords()));
    }

    for (var i = 0; i < query.terms().size(); i++) {
      addTerm(where, order, parameters, rankParameters, i, query.terms().get(i));
    }

    if (!order.isEmpty()) {
      order.insert(0, "(").append("0) desc, ");
    }

    return new Built(
        where.toString(), order.toString(), Map.copyOf(parameters), Map.copyOf(rankParameters));
  }

  /**
   * A term matches the key, the description or a keyword by substring, as the ranking of the search
   * does in memory. The order counts a whole-name match as 2 and a prefix as 1 for each term.
   */
  private static void addTerm(
      final StringBuilder where,
      final StringBuilder order,
      final Map<String, Object> parameters,
      final Map<String, Object> rankParameters,
      final int index,
      final String term) {

    final var needle = term.startsWith("@") ? term.substring(1) : term;
    final var contains = "c" + index;
    final var prefix = "s" + index;
    final var exact = "e" + index;

    parameters.put(contains, "%" + escape(needle) + "%");
    rankParameters.put(prefix, escape(needle) + "%");
    rankParameters.put(exact, needle);

    where
        .append(" and (")
        .append(KEY)
        .append(LIKE.formatted(contains))
        .append(" or lower(pv.description)")
        .append(LIKE.formatted(contains))
        .append(" or ")
        .append(keywordExists("lower(k.keyword)" + LIKE.formatted(contains)))
        .append(")");

    order
        .append("case when lower(p.name) = :")
        .append(exact)
        .append(" or ")
        .append(KEY)
        .append(" = :")
        .append(exact)
        .append(" then 2 when lower(p.name)")
        .append(LIKE.formatted(prefix))
        .append(" or ")
        .append(KEY)
        .append(LIKE.formatted(prefix))
        .append(" then 1 else 0 end + ");
  }

  private static String keywordExists(final String condition) {
    return "exists (select k.id from PackageKeyword k where k.packageVersion = pv and "
        + condition
        + ")";
  }

  private static String escape(final String term) {
    return term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
  }
}
