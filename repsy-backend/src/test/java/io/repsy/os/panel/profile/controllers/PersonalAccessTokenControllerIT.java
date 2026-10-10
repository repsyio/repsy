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
package io.repsy.os.panel.profile.controllers;

import static io.repsy.os.server.shared.http.BareBodyAssertions.expectBare;
import static io.repsy.os.server.shared.http.BareBodyAssertions.expectCreated;
import static io.repsy.os.server.shared.http.BareBodyAssertions.expectNoContent;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.CACHE_CONTROL;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import io.repsy.os.AbstractIT;
import io.repsy.os.shared.token.dtos.TokenScope;
import io.repsy.os.shared.token.entities.PersonalAccessToken;
import io.repsy.os.shared.token.repositories.PersonalAccessTokenRepository;
import io.repsy.os.shared.token.services.PersonalAccessTokenService;
import io.repsy.os.shared.token.utils.TokenFactory;
import io.repsy.os.shared.token.utils.TokenHash;
import io.repsy.os.shared.user.entities.User;
import io.repsy.os.shared.user.entities.UserRole;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Full-stack tests for {@code /api/profile/access-tokens/*}: the real Spring context, MVC dispatch
 * and a containerized PostgreSQL database, with the access token of a login as the credential.
 *
 * <p>Nothing authenticates with a personal access token yet, and these tests pin that on purpose: a
 * token is refused wherever a bearer is read, so the day a route starts to accept one is a
 * deliberate change of this file.
 *
 * <p>Every test runs in one transaction that is rolled back afterwards.
 */
@DisplayName("PersonalAccessTokenController /api/profile/access-tokens/*")
class PersonalAccessTokenControllerIT extends AbstractIT {

  private static final String BASE = "/api/profile/access-tokens";
  private static final String SECRET_PATTERN = "rut-[A-Za-z0-9_-]{43}";
  private static final String VALIDATION_TEXT = "Incoming data couldn't be validated.";
  private static final String TOKEN_NOT_FOUND_TEXT = "Access token not found.";
  private static final String NOT_AN_ACCESS_TOKEN_TEXT =
      "This request is not authenticated with an access token.";
  private static final String LIMIT_TEXT =
      "You already have the maximum number of access tokens that have not expired (50). Revoke one first.";

  private static final String[] CREATED_KEYS = {
    "id", "name", "scopes", "expirationDate", "createdAt", "token"
  };
  private static final String[] LIST_ITEM_KEYS = {
    "id", "name", "scopes", "expirationDate", "createdAt"
  };
  private static final String[] PAGE_KEYS = {"size", "number", "totalElements", "totalPages"};

  @Autowired private PersonalAccessTokenRepository tokenRepository;
  @Autowired private PersonalAccessTokenService tokenService;

  private User alice;
  private User bob;
  private String aliceBearer;
  private String bobBearer;

  @BeforeEach
  void setUp() {
    this.alice = this.createUser(uniqueUsername("alice"), UserRole.USER);
    this.bob = this.createUser(uniqueUsername("bob"), UserRole.USER);
    this.aliceBearer = this.bearerTokenFor(this.alice);
    this.bobBearer = this.bearerTokenFor(this.bob);
  }

  // ---------------------------------------------------------------------------------------------
  // Request and fixture helpers
  // ---------------------------------------------------------------------------------------------

  private ResultActions create(final String bearer, final String json) throws Exception {
    return this.perform(
        post(BASE)
            .with(apiPort())
            .header(AUTHORIZATION, bearer)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json));
  }

  private ResultActions list(final String bearer, final String... params) throws Exception {
    final var request = get(BASE).with(apiPort()).header(AUTHORIZATION, bearer);

    for (int i = 0; i < params.length; i += 2) {
      request.param(params[i], params[i + 1]);
    }

    return this.perform(request);
  }

  private ResultActions revoke(final String bearer, final Object tokenId) throws Exception {
    return this.perform(delete(BASE + "/" + tokenId).with(apiPort()).header(AUTHORIZATION, bearer));
  }

  private ResultActions current(final String bearer) throws Exception {
    return this.perform(get(BASE + "/current").with(apiPort()).header(AUTHORIZATION, bearer));
  }

  private static String form(final String name, final String... scopes) {
    final var quoted = Stream.of(scopes).map(scope -> "\"" + scope + "\"").toList();

    return "{\"name\":\"%s\",\"scopes\":[%s]}".formatted(name, String.join(",", quoted));
  }

  /** Writes a token row directly, as the application would, and returns it re-read. */
  private PersonalAccessToken seed(
      final User owner,
      final String name,
      final EnumSet<TokenScope> scopes,
      final Instant expirationDate) {
    final var token = new PersonalAccessToken();

    token.setUser(owner);
    token.setName(name);
    token.setTokenHash(TokenHash.hash(TokenFactory.personalAccessToken()));
    token.setScopes(scopes);
    token.setExpirationDate(expirationDate);
    this.tokenRepository.saveAndFlush(token);

    return this.reload(token.getId());
  }

  private PersonalAccessToken seed(final User owner, final String name) {
    return this.seed(
        owner,
        name,
        EnumSet.of(TokenScope.PROFILE_READ, TokenScope.REPO_READ),
        Instant.now().plus(Duration.ofDays(30)));
  }

  private PersonalAccessToken reload(final UUID tokenId) {
    this.entityManager.flush();
    this.entityManager.clear();

    return this.tokenRepository.findById(tokenId).orElseThrow();
  }

  private void setCreatedAt(final PersonalAccessToken token, final Instant createdAt) {
    this.entityManager.flush();
    this.entityManager
        .createNativeQuery("update personal_access_token set created_at = ?1 where id = ?2")
        .setParameter(1, Timestamp.from(createdAt))
        .setParameter(2, token.getId())
        .executeUpdate();
    this.entityManager.clear();
  }

  private long countOf(final User owner) {
    this.entityManager.flush();
    this.entityManager.clear();

    return this.tokenRepository.countByUserId(owner.getId());
  }

  private static String idOf(final String body) {
    return JsonPath.read(body, "$.id");
  }

  private static long number(final Object json) {
    return ((Number) json).longValue();
  }

  private static List<String> namesOf(final String body) {
    return JsonPath.read(body, "$.content[*].name");
  }

  private static void expectValidationError(final ResultActions result, final String data)
      throws Exception {
    expectError(result, HttpStatus.BAD_REQUEST, "validationError", data, VALIDATION_TEXT);
  }

  private static void expectTokenNotFound(final ResultActions result) throws Exception {
    expectError(
        result,
        HttpStatus.NOT_FOUND,
        "accessTokenNotFound",
        "accessTokenNotFound",
        TOKEN_NOT_FOUND_TEXT);
  }

  // ---------------------------------------------------------------------------------------------
  // Create
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName(
      "POST creates a token: 201, a Location, no-store, the secret once, the scopes sorted")
  void createsAToken() throws Exception {
    final var result = this.create(this.aliceBearer, form("ci", "repo:write", "repo:read"));
    final var body = expectCreated(result);

    result.andExpect(
        header().string(CACHE_CONTROL, org.hamcrest.Matchers.containsString("no-store")));

    final Map<String, Object> json = JsonPath.read(body, "$");
    assertThat(json).containsOnlyKeys(CREATED_KEYS);
    assertThat((String) json.get("token")).matches(SECRET_PATTERN);
    assertThat(json.get("id")).asString().matches(UUID_PATTERN);
    assertThat(json).containsEntry("name", "ci");
    assertThat(JsonPath.<List<String>>read(body, "$.scopes"))
        .containsExactly("profile:read", "repo:read", "repo:write");
    assertThat(result.andReturn().getResponse().getHeader(LOCATION))
        .isEqualTo(BASE + "/" + json.get("id"));
    assertThat(Instant.parse((String) json.get("createdAt"))).isNotNull();
  }

  @Test
  @DisplayName("POST stores the hash of the secret and the secret nowhere")
  void storesOnlyTheHash() throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:read")));
    final String secret = JsonPath.read(body, "$.token");
    final var id = UUID.fromString(idOf(body));

    final var stored = this.reload(id);

    assertThat(stored.getTokenHash()).isEqualTo(TokenHash.hash(secret)).hasSize(64);
    assertThat(stored.getTokenHash()).isNotEqualTo(secret);
    assertThat(stored.getUser().getId()).isEqualTo(this.alice.getId());
    assertThat(stored.getName()).isEqualTo("ci");
    assertThat(stored.getScopes()).containsExactly(TokenScope.PROFILE_READ, TokenScope.REPO_READ);
    assertThat(stored.toString()).doesNotContain(secret).doesNotContain(stored.getTokenHash());

    final var columns =
        this.entityManager
            .createNativeQuery(
                "select name || '|' || token_hash || '|' || scopes from personal_access_token"
                    + " where id = ?1")
            .setParameter(1, id)
            .getSingleResult()
            .toString();

    assertThat(columns).doesNotContain(secret);
    assertThat(JsonPath.<String>read(expectBare(this.list(this.aliceBearer)), "$.content[0].name"))
        .isEqualTo("ci");
  }

  @Test
  @DisplayName("POST stores the scopes as one comma separated string in canonical order")
  void storesTheScopesCanonically() throws Exception {
    final var body =
        expectCreated(
            this.create(this.aliceBearer, form("ci", "scan:read", "repo:manage", "repo:read")));
    final var id = UUID.fromString(idOf(body));

    this.entityManager.flush();
    final var column =
        this.entityManager
            .createNativeQuery("select scopes from personal_access_token where id = ?1")
            .setParameter(1, id)
            .getSingleResult();

    assertThat(column).isEqualTo("profile:read,repo:manage,repo:read,scan:read");
  }

  @Test
  @DisplayName("POST expires a token without a date in 365 days")
  void defaultsTheExpiryToAYear() throws Exception {
    final var before = Instant.now();

    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:read")));

    assertThat(Instant.parse(JsonPath.read(body, "$.expirationDate")))
        .isBetween(before.plus(Duration.ofDays(365)), Instant.now().plus(Duration.ofDays(365)));
  }

  @Test
  @DisplayName("POST answers with the date that is stored: the same instant, to the microsecond")
  void theAnswerIsTheStoredDate() throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:read")));

    final var stored = this.reload(UUID.fromString(idOf(body))).getExpirationDate();

    assertThat(stored)
        .isEqualTo(Instant.parse(JsonPath.<String>read(body, "$.expirationDate")))
        .isEqualTo(stored.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
  }

  @Test
  @DisplayName("POST never gives repo:manage to a token that did not ask for it")
  void doesNotImplyManage() throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:write")));

    assertThat(JsonPath.<List<String>>read(body, "$.scopes")).doesNotContain("repo:manage");
  }

  @Test
  @DisplayName(
      "POST gives a token only profile:read plus what it asked for, and allows names of 80")
  void acceptsTheLongestName() throws Exception {
    final var name = "n".repeat(80);

    final var body = expectCreated(this.create(this.aliceBearer, form(name, "profile:read")));

    assertThat(JsonPath.<String>read(body, "$.name")).isEqualTo(name);
    assertThat(JsonPath.<List<String>>read(body, "$.scopes")).containsExactly("profile:read");
  }

  static Stream<Arguments> invalidForms() {
    return Stream.of(
        Arguments.of("no name", "{\"scopes\":[\"repo:read\"]}"),
        Arguments.of("an empty name", form("", "repo:read")),
        Arguments.of("a name of 81 characters", form("n".repeat(81), "repo:read")),
        Arguments.of("no scopes", "{\"name\":\"ci\"}"),
        Arguments.of("an empty list of scopes", form("ci")),
        Arguments.of("a scope that does not exist", form("ci", "repo:admin")),
        Arguments.of("a scope in the wrong case", form("ci", "REPO:READ")),
        Arguments.of("a scope with a space", form("ci", " repo:read")),
        Arguments.of("null as a scope", "{\"name\":\"ci\",\"scopes\":[null]}"),
        Arguments.of("scopes that are not a list", "{\"name\":\"ci\",\"scopes\":\"repo:read\"}"),
        Arguments.of(
            "a date that is not a date",
            "{\"name\":\"ci\",\"scopes\":[\"repo:read\"],\"expirationDate\":\"soon\"}"),
        Arguments.of("a body that is not JSON", "not json"));
  }

  @ParameterizedTest(name = "POST refuses {0} with 400 and stores nothing")
  @MethodSource("invalidForms")
  @DisplayName("POST validation")
  void refusesAnInvalidForm(final String ignoredCase, final String json) throws Exception {
    this.create(this.aliceBearer, json).andExpect(status().isBadRequest());

    assertThat(this.countOf(this.alice)).isZero();
  }

  @Test
  @DisplayName("POST answers a missing name with validationError naming the field")
  void namesTheInvalidField() throws Exception {
    expectValidationError(this.create(this.aliceBearer, "{\"scopes\":[\"repo:read\"]}"), "name");
    expectValidationError(this.create(this.aliceBearer, "{\"name\":\"ci\"}"), "scopes");
  }

  @Test
  @DisplayName("two tokens with the same name are two tokens, and each has its own secret")
  void namesAreNotUnique() throws Exception {
    final var first = expectCreated(this.create(this.aliceBearer, form("ci", "repo:read")));
    final var second = expectCreated(this.create(this.aliceBearer, form("ci", "repo:read")));

    assertThat(idOf(first)).isNotEqualTo(idOf(second));
    assertThat(JsonPath.<String>read(first, "$.token"))
        .isNotEqualTo(JsonPath.<String>read(second, "$.token"));
    assertThat(this.countOf(this.alice)).isEqualTo(2);
  }

  // ---------------------------------------------------------------------------------------------
  // The limit of 50
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("POST refuses the 51st token with accessTokenLimitReached; revoking one makes room")
  void capsTheTokensOfAUser() throws Exception {
    for (int i = 0; i < PersonalAccessTokenService.MAX_TOKENS_PER_USER; i++) {
      this.seed(this.alice, "token-" + i);
    }
    assertThat(this.countOf(this.alice)).isEqualTo(50);

    expectError(
        this.create(this.aliceBearer, form("one-too-many", "repo:read")),
        HttpStatus.BAD_REQUEST,
        "accessTokenLimitReached",
        "accessTokenLimitReached",
        LIMIT_TEXT);
    assertThat(this.countOf(this.alice)).isEqualTo(50);

    final var first =
        this.tokenRepository.findAll().stream()
            .filter(token -> token.getUser().getId().equals(this.alice.getId()))
            .findFirst()
            .orElseThrow();
    expectNoContent(this.revoke(this.aliceBearer, first.getId()));

    expectCreated(this.create(this.aliceBearer, form("fits-now", "repo:read")));
    assertThat(this.countOf(this.alice)).isEqualTo(50);
  }

  private PersonalAccessToken seedExpired(final User owner, final String name) {
    return this.seed(
        owner,
        name,
        EnumSet.of(TokenScope.PROFILE_READ, TokenScope.REPO_READ),
        Instant.now().minus(Duration.ofDays(1)));
  }

  @Test
  @DisplayName(
      "POST does not count expired tokens: a user at the limit with one expired can create")
  void expiredTokensDoNotCountTowardsTheLimit() throws Exception {
    for (int i = 0; i < PersonalAccessTokenService.MAX_TOKENS_PER_USER - 1; i++) {
      this.seed(this.alice, "valid-" + i);
    }
    final var expired = this.seedExpired(this.alice, "expired");
    assertThat(this.countOf(this.alice)).as("rows").isEqualTo(50);

    expectCreated(this.create(this.aliceBearer, form("fits", "repo:read")));

    assertThat(this.countOf(this.alice)).as("rows: the expired one is still there").isEqualTo(51);
    assertThat(this.tokenRepository.findById(expired.getId())).isPresent();
  }

  @Test
  @DisplayName(
      "POST counts the 50 that have not expired, and refuses the next one, whatever expired")
  void theLimitIsOfTheTokensThatHaveNotExpired() throws Exception {
    for (int i = 0; i < PersonalAccessTokenService.MAX_TOKENS_PER_USER; i++) {
      this.seed(this.alice, "valid-" + i);
    }
    this.seedExpired(this.alice, "expired-1");
    this.seedExpired(this.alice, "expired-2");

    expectError(
        this.create(this.aliceBearer, form("one-too-many", "repo:read")),
        HttpStatus.BAD_REQUEST,
        "accessTokenLimitReached",
        "accessTokenLimitReached",
        LIMIT_TEXT);
    assertThat(this.countOf(this.alice)).isEqualTo(52);
  }

  @Test
  @DisplayName("POST allows a user whose tokens have all expired, however many there are")
  void aUserWithOnlyExpiredTokensCanCreate() throws Exception {
    for (int i = 0; i < PersonalAccessTokenService.MAX_TOKENS_PER_USER + 5; i++) {
      this.seedExpired(this.alice, "old-" + i);
    }

    expectCreated(this.create(this.aliceBearer, form("fresh", "repo:read")));
  }

  @Test
  @DisplayName("an expired token stays in the list and can still be revoked")
  void anExpiredTokenIsListedAndRevocable() throws Exception {
    final var expired = this.seedExpired(this.alice, "old");

    final var body = expectBare(this.list(this.aliceBearer));

    assertThat(namesOf(body)).containsExactly("old");
    expectNoContent(this.revoke(this.aliceBearer, expired.getId()));
    assertThat(this.countOf(this.alice)).isZero();
  }

  @Test
  @DisplayName("the limit is per user: another user is not held back by it")
  void theLimitIsPerUser() throws Exception {
    for (int i = 0; i < PersonalAccessTokenService.MAX_TOKENS_PER_USER; i++) {
      this.seed(this.alice, "token-" + i);
    }

    expectCreated(this.create(this.bobBearer, form("bobs", "repo:read")));
  }

  // ---------------------------------------------------------------------------------------------
  // List
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("GET lists the tokens of the caller only, with no secret and no hash")
  void listsOnlyOwnTokens() throws Exception {
    final var mine = this.seed(this.alice, "mine-1");
    this.seed(this.alice, "mine-2");
    this.seed(this.alice, "mine-3");
    this.seed(this.bob, "not-mine-1");
    this.seed(this.bob, "not-mine-2");

    final var body = expectBare(this.list(this.aliceBearer));

    final Map<String, Object> page = JsonPath.read(body, "$.page");
    assertThat(page).containsOnlyKeys(PAGE_KEYS);
    assertThat(number(page.get("totalElements"))).isEqualTo(3);
    assertThat(namesOf(body)).containsExactlyInAnyOrder("mine-1", "mine-2", "mine-3");
    assertThat(body)
        .doesNotContain("not-mine")
        .doesNotContain("tokenHash")
        .doesNotContain("\"token\"");
    assertThat(body).doesNotContain(mine.getTokenHash());

    final List<Map<String, Object>> content = JsonPath.read(body, "$.content");
    for (final var item : content) {
      assertThat(item).containsOnlyKeys(LIST_ITEM_KEYS);
    }
  }

  @Test
  @DisplayName("GET shows scopes in canonical order and the last use only once there was one")
  void listsTheShapeOfAnItem() throws Exception {
    final var token =
        this.seed(
            this.alice,
            "shaped",
            EnumSet.of(TokenScope.SCAN_READ, TokenScope.REPO_WRITE, TokenScope.PROFILE_READ),
            Instant.now().plus(Duration.ofDays(10)));

    final var before = expectBare(this.list(this.aliceBearer));

    assertThat(JsonPath.<List<String>>read(before, "$.content[0].scopes"))
        .containsExactly("profile:read", "repo:write", "scan:read");
    assertThat(JsonPath.<Map<String, Object>>read(before, "$.content[0]"))
        .containsOnlyKeys(LIST_ITEM_KEYS)
        .containsEntry("id", token.getId().toString());

    this.tokenService.updateLastUsedTime(token.getId());
    this.entityManager.flush();
    this.entityManager.clear();

    final var after = expectBare(this.list(this.aliceBearer));

    assertThat(JsonPath.<Map<String, Object>>read(after, "$.content[0]")).containsKey("lastUsedAt");
    assertThat(Instant.parse(JsonPath.read(after, "$.content[0].lastUsedAt")))
        .isBetween(Instant.now().minusSeconds(30), Instant.now().plusSeconds(1));
  }

  @Test
  @DisplayName("GET answers an empty page for a user without tokens")
  void listsNothingForAUserWithoutTokens() throws Exception {
    final var body = expectBare(this.list(this.aliceBearer));

    assertThat(JsonPath.<List<Object>>read(body, "$.content")).isEmpty();
    assertThat(number(JsonPath.<Object>read(body, "$.page.totalElements"))).isZero();
    assertThat(number(JsonPath.<Object>read(body, "$.page.size"))).isEqualTo(10);
  }

  @Test
  @DisplayName("GET pages the list: size, page and totals")
  void pagesTheList() throws Exception {
    for (int i = 0; i < 5; i++) {
      this.seed(this.alice, "t-" + i);
    }

    final var first = expectBare(this.list(this.aliceBearer, "size", "2"));
    final var third = expectBare(this.list(this.aliceBearer, "size", "2", "page", "2"));

    assertThat(namesOf(first)).hasSize(2);
    assertThat(namesOf(third)).hasSize(1);
    assertThat(number(JsonPath.<Object>read(first, "$.page.totalPages"))).isEqualTo(3);
    assertThat(number(JsonPath.<Object>read(third, "$.page.number"))).isEqualTo(2);
  }

  @Test
  @DisplayName("GET sorts by name, either way, and by id descending by default")
  void sortsTheList() throws Exception {
    final var charlie = this.seed(this.alice, "charlie");
    final var alpha = this.seed(this.alice, "alpha");
    final var bravo = this.seed(this.alice, "bravo");

    assertThat(namesOf(expectBare(this.list(this.aliceBearer, "sort", "name,asc"))))
        .containsExactly("alpha", "bravo", "charlie");
    assertThat(namesOf(expectBare(this.list(this.aliceBearer, "sort", "name,desc"))))
        .containsExactly("charlie", "bravo", "alpha");

    final var byDefault = namesOf(expectBare(this.list(this.aliceBearer)));
    final var ids =
        List.of(charlie, alpha, bravo).stream()
            .sorted((a, b) -> b.getId().compareTo(a.getId()))
            .map(PersonalAccessToken::getName)
            .toList();

    assertThat(byDefault).containsExactlyElementsOf(ids);
  }

  @Test
  @DisplayName("GET sorts by expirationDate and by createdAt")
  void sortsByDates() throws Exception {
    final var soon =
        this.seed(
            this.alice,
            "soon",
            EnumSet.of(TokenScope.PROFILE_READ),
            Instant.now().plus(Duration.ofDays(1)));
    final var later =
        this.seed(
            this.alice,
            "later",
            EnumSet.of(TokenScope.PROFILE_READ),
            Instant.now().plus(Duration.ofDays(200)));
    this.setCreatedAt(soon, Instant.now().minus(Duration.ofDays(2)));
    this.setCreatedAt(later, Instant.now().minus(Duration.ofDays(9)));

    assertThat(namesOf(expectBare(this.list(this.aliceBearer, "sort", "expirationDate,asc"))))
        .containsExactly("soon", "later");
    assertThat(namesOf(expectBare(this.list(this.aliceBearer, "sort", "createdAt,asc"))))
        .containsExactly("later", "soon");
    assertThat(namesOf(expectBare(this.list(this.aliceBearer, "sort", "createdAt,desc"))))
        .containsExactly("soon", "later");
  }

  @ParameterizedTest(name = "GET sort={0} is refused with 400 validationError")
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {"scopes,asc", "tokenHash,asc", "token,asc", "user.username,asc", "nope,asc"})
  @DisplayName("GET refuses a property it cannot sort by")
  void refusesAnUnsortableProperty(final String sort) throws Exception {
    expectValidationError(this.list(this.aliceBearer, "sort", sort), "sort");
  }

  // ---------------------------------------------------------------------------------------------
  // Revoke
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("DELETE revokes a token: 204, the row is gone and its secret finds nothing")
  void revokesAToken() throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:read")));
    final String secret = JsonPath.read(body, "$.token");
    final var id = UUID.fromString(idOf(body));

    expectNoContent(this.revoke(this.aliceBearer, id));

    this.entityManager.flush();
    this.entityManager.clear();
    assertThat(this.tokenRepository.findById(id)).isEmpty();
    assertThat(this.tokenService.findByToken(secret)).isEmpty();
    assertThat(namesOf(expectBare(this.list(this.aliceBearer)))).isEmpty();
  }

  @Test
  @DisplayName("DELETE revokes the one token and leaves the others")
  void revokesOnlyThatToken() throws Exception {
    final var keep = this.seed(this.alice, "keep");
    final var drop = this.seed(this.alice, "drop");

    expectNoContent(this.revoke(this.aliceBearer, drop.getId()));

    assertThat(namesOf(expectBare(this.list(this.aliceBearer)))).containsExactly("keep");
    assertThat(this.tokenRepository.findById(keep.getId())).isPresent();
  }

  @Test
  @DisplayName("DELETE answers 404 for a token of another user, and does not delete it")
  void doesNotRevokeAnotherUsersToken() throws Exception {
    final var bobs = this.seed(this.bob, "bobs");

    expectTokenNotFound(this.revoke(this.aliceBearer, bobs.getId()));

    assertThat(this.tokenRepository.findById(bobs.getId())).isPresent();
    assertThat(this.countOf(this.bob)).isEqualTo(1);
  }

  @Test
  @DisplayName("DELETE answers 404 for a token that does not exist, and for one already revoked")
  void answers404ForAMissingToken() throws Exception {
    expectTokenNotFound(this.revoke(this.aliceBearer, UUID.randomUUID()));

    final var token = this.seed(this.alice, "once");
    expectNoContent(this.revoke(this.aliceBearer, token.getId()));
    expectTokenNotFound(this.revoke(this.aliceBearer, token.getId()));
  }

  @Test
  @DisplayName("DELETE answers 400 for an id that is not a UUID")
  void refusesAMalformedId() throws Exception {
    this.revoke(this.aliceBearer, "not-a-uuid").andExpect(status().isBadRequest());
  }

  // ---------------------------------------------------------------------------------------------
  // Current
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("GET /current answers 400 notAnAccessToken for the access token of a login")
  void currentRefusesALoginToken() throws Exception {
    expectError(
        this.current(this.aliceBearer),
        HttpStatus.BAD_REQUEST,
        "notAnAccessToken",
        "notAnAccessToken",
        NOT_AN_ACCESS_TOKEN_TEXT);
  }

  @Test
  @DisplayName("the service says who a token is, what it may do and until when")
  void theServiceSaysWhatATokenIs() throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:write")));
    final var id = UUID.fromString(idOf(body));

    final var whoAmI = this.tokenService.getWhoAmI(id);

    assertThat(whoAmI.getUsername()).isEqualTo(this.alice.getUsername());
    assertThat(whoAmI.getName()).isEqualTo("ci");
    assertThat(whoAmI.getScopes()).containsExactly(TokenScope.PROFILE_READ, TokenScope.REPO_WRITE);
    assertThat(whoAmI.getExpirationDate())
        .isEqualTo(Instant.parse(JsonPath.read(body, "$.expirationDate")));
    assertThat(whoAmI.getLastUsedAt()).isNull();
  }

  @Test
  @DisplayName("the service finds a token by its secret, and by nothing else")
  void theServiceFindsATokenBySecret() throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:read")));
    final String secret = JsonPath.read(body, "$.token");

    final var found = this.tokenService.findByToken(secret);

    assertThat(found).isPresent();
    assertThat(found.get().id()).hasToString(idOf(body));
    assertThat(found.get().userId()).isEqualTo(this.alice.getId());
    assertThat(found.get().username()).isEqualTo(this.alice.getUsername());
    assertThat(found.get().isExpired()).isFalse();
    assertThat(this.tokenService.findByToken(secret + "x")).isEmpty();
    assertThat(this.tokenService.findByToken(TokenHash.hash(secret))).isEmpty();
    assertThat(this.tokenService.findByToken(idOf(body))).isEmpty();
  }

  // ---------------------------------------------------------------------------------------------
  // Authentication: a login token only
  // ---------------------------------------------------------------------------------------------

  private record Endpoint(
      String name, Function<String, MockHttpServletRequestBuilder> request, boolean hasBody) {
    @Override
    public String toString() {
      return this.name;
    }
  }

  private static MockHttpServletRequestBuilder withBearer(
      final MockHttpServletRequestBuilder builder, final String bearer) {
    return bearer == null ? builder : builder.header(AUTHORIZATION, bearer);
  }

  static Stream<Endpoint> endpoints() {
    return Stream.of(
        new Endpoint(
            "POST " + BASE,
            bearer ->
                withBearer(post(BASE), bearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(form("ci", "repo:read")),
            true),
        new Endpoint("GET " + BASE, bearer -> withBearer(get(BASE), bearer), false),
        new Endpoint(
            "GET " + BASE + "/current",
            bearer -> withBearer(get(BASE + "/current"), bearer),
            false),
        new Endpoint(
            "DELETE " + BASE + "/{tokenId}",
            bearer -> withBearer(delete(BASE + "/" + UUID.randomUUID()), bearer),
            false));
  }

  @ParameterizedTest(name = "{0} without credentials is 401")
  @MethodSource("endpoints")
  @DisplayName("every route refuses a request with no credentials")
  void refusesNoCredentials(final Endpoint endpoint) throws Exception {
    this.perform(endpoint.request().apply(null).with(apiPort()))
        .andExpect(status().isUnauthorized());
  }

  @ParameterizedTest(name = "{0} with a token that is not a login token of this panel is 401")
  @MethodSource("endpoints")
  @DisplayName("every route refuses a token of another kind")
  void refusesTokensOfOtherKinds(final Endpoint endpoint) throws Exception {
    final var bearers =
        List.of(
            "Bearer not-a-jwt",
            this.protocolBearerTokenFor(this.alice),
            this.expiredBearerTokenFor(this.alice),
            this.claimlessBearerTokenFor(this.alice),
            basicAuth(this.alice.getUsername(), VALID_PASSWORD));

    for (final var bearer : bearers) {
      this.perform(endpoint.request().apply(bearer).with(apiPort()))
          .andExpect(status().isUnauthorized());
    }
  }

  /** The routes that manage tokens: a personal access token cannot mint or revoke tokens. */
  static Stream<Endpoint> managementEndpoints() {
    return endpoints().filter(endpoint -> !endpoint.name().endsWith("/current"));
  }

  @ParameterizedTest(name = "{0} does not accept a personal access token")
  @MethodSource("managementEndpoints")
  @DisplayName("the routes that manage tokens refuse a personal access token (RPS-1903)")
  void refusesAPersonalAccessToken(final Endpoint endpoint) throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:manage")));
    final String secret = JsonPath.read(body, "$.token");

    this.perform(endpoint.request().apply("Bearer " + secret).with(apiPort()))
        .andExpect(status().isUnauthorized());
    this.perform(endpoint.request().apply(basicAuth("anyone", secret)).with(apiPort()))
        .andExpect(status().isUnauthorized());

    assertThat(this.countOf(this.alice)).isEqualTo(1);
  }

  @Test
  @DisplayName("GET /current answers who a personal access token is, what it may do and until when")
  void currentAnswersWhatThePersonalAccessTokenIs() throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:write")));
    final String secret = JsonPath.read(body, "$.token");

    this.current("Bearer " + secret)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.username").value(this.alice.getUsername()))
        .andExpect(jsonPath("$.name").value("ci"))
        .andExpect(jsonPath("$.scopes[0]").value("profile:read"))
        .andExpect(jsonPath("$.scopes[1]").value("repo:write"))
        .andExpect(
            jsonPath("$.expirationDate").value((String) JsonPath.read(body, "$.expirationDate")));

    // It is the token's own use: the answer after it says when. The test runs in one transaction,
    // so the row the update touched is read again from the database.
    this.entityManager.flush();
    this.entityManager.clear();

    this.current("Bearer " + secret)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lastUsedAt").isNotEmpty());
  }

  @Test
  @DisplayName("GET /current refuses a personal access token sent as Basic credentials")
  void currentRefusesABasicPersonalAccessToken() throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:read")));
    final String secret = JsonPath.read(body, "$.token");

    this.current(basicAuth(this.alice.getUsername(), secret)).andExpect(status().isUnauthorized());
  }

  @Test
  @DisplayName("GET /current refuses an unknown, an expired and a revoked personal access token")
  void currentRefusesATokenThatIsNotLive() throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("ci", "repo:read")));
    final String secret = JsonPath.read(body, "$.token");
    final var id = idOf(body);

    this.current("Bearer " + TokenFactory.personalAccessToken())
        .andExpect(status().isUnauthorized());

    this.revoke(this.aliceBearer, id).andExpect(status().isNoContent());

    this.current("Bearer " + secret).andExpect(status().isUnauthorized());
  }

  // ---------------------------------------------------------------------------------------------
  // The user's token and the user's account
  // ---------------------------------------------------------------------------------------------

  @Test
  @DisplayName("deleting the account revokes every token of the user, and only theirs")
  void deletingTheUserRevokesTheirTokens() throws Exception {
    this.seed(this.alice, "a-1");
    this.seed(this.alice, "a-2");
    this.seed(this.bob, "b-1");

    expectNoContent(
        this.perform(
            delete("/api/profile").with(apiPort()).header(AUTHORIZATION, this.aliceBearer)));

    assertThat(this.countOf(this.alice)).isZero();
    assertThat(this.countOf(this.bob)).isEqualTo(1);
  }

  @Test
  @DisplayName("a token of one user is not listed to, nor revocable by, another")
  void usersDoNotSeeEachOther() throws Exception {
    final var body = expectCreated(this.create(this.aliceBearer, form("alices", "repo:read")));

    assertThat(namesOf(expectBare(this.list(this.bobBearer)))).isEmpty();
    expectTokenNotFound(this.revoke(this.bobBearer, idOf(body)));
    assertThat(namesOf(expectBare(this.list(this.aliceBearer)))).containsExactly("alices");
  }

  @Test
  @DisplayName("the admin has tokens of their own like anyone, and sees nobody else's")
  void anAdminSeesOnlyTheirOwnTokens() throws Exception {
    this.seed(this.alice, "alices");

    final var body = expectBare(this.list(this.adminBearerToken()));

    assertThat(namesOf(body)).doesNotContain("alices");
  }
}
