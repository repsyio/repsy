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
package io.repsy.os.shared.messages;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fails when a message id used by the backend has no entry in {@code messages.properties}.
 *
 * <p>{@code RestResponseFactory} falls back to the raw msgId as the response {@code text} when the
 * bundle has no entry for it, so a missing key never fails anything at runtime (RPS-895, RPS-907).
 * This test scans the main sources of {@code repsy-backend}, {@code libs} and {@code
 * repsy-protocols} instead of driving the endpoints, so it needs no Docker and also sees the msgIds
 * of errors thrown from services and protocol modules, which {@code ErrorHandler} turns into
 * responses.
 *
 * <p>What it recognises: an identifier-like string literal or an {@code UPPER_SNAKE} constant that
 * is the first argument of {@code success}/{@code warning}/{@code error} on the response factory,
 * or of a msgId-carrying exception constructor. Ids held in variables, built by concatenation, or
 * free text with spaces are not seen.
 *
 * <p>Bundle keys that no code uses are not flagged: most of them are leftovers tracked by RPS-959.
 */
@DisplayName("messages.properties")
class MessageKeysTest {

  private static final String BUNDLE = "/messages.properties";

  /** Surefire runs with the module directory ({@code repsy-backend}) as the working directory. */
  private static final Path MODULE_DIR = Path.of("");

  /** Cargo, docker, ... modules and the shared libraries live next to {@code repsy-backend}. */
  private static final List<Path> SIBLING_DIRS =
      List.of(Path.of("..", "libs"), Path.of("..", "repsy-protocols"));

  private static final String LITERAL_OR_CONSTANT =
      "(?:\"(?<literal>\\w+)\"|(?<constant>(?:\\w+\\.)*[A-Z][A-Z0-9_]*))(?=\\s*[,)])";

  private static final Pattern RESPONSE_MSG_ID =
      Pattern.compile(
          "\\b(?:resp|responseFactory|restResponseFactory)\\s*\\.\\s*(?:success|warning|error)\\(\\s*"
              + LITERAL_OR_CONSTANT);

  /** Exceptions {@code ErrorHandler} renders with the exception message as the msgId. */
  private static final Pattern EXCEPTION_MSG_ID =
      Pattern.compile(
          "\\bnew\\s+(?:ItemNotFound|BadRequest|ItemAlreadyExist|AccessNotAllowed|UnAuthorized"
              + "|ErrorOccurred|SignatureNotVerified|Mfa)Exception\\(\\s*"
              + LITERAL_OR_CONSTANT);

  /**
   * Helpers that pass their last argument on as the msgId of an exception, so the literal never
   * sits next to a {@code new ...Exception(}.
   */
  private static final Pattern FORWARDED_MSG_ID =
      Pattern.compile(
          "\\b(?:stringField|requireGemField)\\((?:[^;()\"]|\"[^\"]*\"|\\([^()]*\\))*,\\s*"
              + "\"(?<literal>\\w+)\"\\s*\\)");

  private static final Pattern CONSTANT_DEFINITION =
      Pattern.compile(
          "static\\s+final\\s+(?:@\\w+\\s+)?String\\s+([A-Z][A-Z0-9_]*)\\s*=\\s*\"([^\"\\\\]*)\"");

  /**
   * ErrorHandler msgIds with no bundle entry yet. RPS-991 adds the entry; delete the id here in
   * that change. {@link #allowlistsHoldOnlyIdsThatAreStillMissing()} fails if one is left behind.
   */
  private static final Set<String> PENDING_RPS_991 = Set.of("movedToPath");

  /**
   * Error msgIds thrown from exceptions with no bundle entry yet. RPS-958 adds the entries or drops
   * the ids; delete each id here in that change.
   */
  private static final Set<String> PENDING_RPS_958 =
      Set.of(
          "blobNotFound",
          "chartNotFound",
          "crateNotFound",
          "crateVersionNotFound",
          "digestMismatch",
          "fileAlreadyExists",
          "gemNameMissing",
          "gemNotFound",
          "gemVersionAlreadyExists",
          "gemVersionAlreadyYanked",
          "gemVersionMissing",
          "gemVersionNotFound",
          "goModFileEmpty",
          "goModInvalidModulePath",
          "goModMissingModuleDirective",
          "goModNotFoundInZip",
          "goModuleVersionAlreadyExists",
          "invalidAuthType",
          "invalidModulePath",
          "invalidPackageVersion",
          "moduleNotFound",
          "nupkgNotFound",
          "nuspecNotFound",
          "packageVersionAlreadyExists",
          "refreshTokenExpired",
          "sha256Mismatch",
          "unknownPath",
          "urlVariablesNotFound",
          "versionNotFound");

  private static Properties messages;
  private static Map<String, Set<String>> usedMsgIds;
  private static Set<String> unresolvedConstants;

  @BeforeAll
  static void scanSources() throws IOException {
    messages = new Properties();

    try (InputStream in = MessageKeysTest.class.getResourceAsStream(BUNDLE)) {
      messages.load(Objects.requireNonNull(in, "messages.properties is not on the classpath"));
    }

    final var sources = readSources();
    final var constants = collectConstants(sources.values());

    usedMsgIds = new TreeMap<>();
    unresolvedConstants = new TreeSet<>();

    sources.forEach(
        (file, source) -> {
          collect(RESPONSE_MSG_ID, source, file, constants);
          collect(EXCEPTION_MSG_ID, source, file, constants);
          collect(FORWARDED_MSG_ID, source, file, constants);
        });
  }

  @Test
  @DisplayName("every msgId used in code has an entry, or is pending in an allowlist")
  void usedMsgIdsHaveEntries() {
    final var missing = new TreeMap<>(usedMsgIds);

    missing.keySet().removeAll(messages.stringPropertyNames());
    missing.keySet().removeAll(PENDING_RPS_991);
    missing.keySet().removeAll(PENDING_RPS_958);

    assertThat(missing)
        .as(
            "msgIds used in code but missing from messages.properties (the API would return the"
                + " raw key as `text`); add an entry for each")
        .isEmpty();
  }

  @Test
  @DisplayName("every msgId constant used in code can be resolved to a string")
  void msgIdConstantsResolve() {
    assertThat(unresolvedConstants)
        .as(
            "msgId constants the scan cannot resolve; define them as `static final String X ="
                + " \"...\"` in the scanned sources")
        .isEmpty();
  }

  @Test
  @DisplayName("the allowlists hold only ids that are still used and still missing")
  void allowlistsHoldOnlyIdsThatAreStillMissing() {
    final var pending = new TreeSet<String>();

    pending.addAll(PENDING_RPS_991);
    pending.addAll(PENDING_RPS_958);

    final var nowHaveEntries =
        pending.stream()
            .filter(messages::containsKey)
            .collect(Collectors.toCollection(TreeSet::new));
    final var noLongerUsed =
        pending.stream()
            .filter(id -> !usedMsgIds.containsKey(id))
            .collect(Collectors.toCollection(TreeSet::new));

    assertThat(nowHaveEntries)
        .as("allowlisted ids that now have an entry; remove them from the allowlist")
        .isEmpty();
    assertThat(noLongerUsed)
        .as("allowlisted ids that no code uses any more; remove them from the allowlist")
        .isEmpty();
  }

  @Test
  @DisplayName("the scan finds each kind of msgId, so it cannot pass vacuously")
  void scanFindsEachKindOfMsgId() {
    // A refactor that breaks a pattern must fail here instead of silently checking nothing.
    assertThat(usedMsgIds.keySet())
        .contains(
            "repoCreated", // literal in a controller
            "packagesFetched", // constant in a controller
            "repoNotFound", // literal in an exception
            "unAuthorized", // ErrorConstants constant in an exception
            "validationError", // constant in ErrorHandler
            "chartNameMissing", // BadRequestException in a protocol module
            "gemNameMissing"); // forwarded through a helper
    assertThat(usedMsgIds).hasSizeGreaterThan(100);
  }

  private static void collect(
      final Pattern pattern,
      final String source,
      final String file,
      final Map<String, Set<String>> constants) {
    final Matcher matcher = pattern.matcher(source);

    while (matcher.find()) {
      final var literal = matcher.group("literal");
      final var constant = groupOrNull(matcher, "constant");

      if (literal != null) {
        addUsage(literal, file);
      } else if (constant != null) {
        final var name = constant.substring(constant.lastIndexOf('.') + 1);
        final var values = constants.get(name);

        if (values == null) {
          unresolvedConstants.add(constant + " (" + file + ")");
        } else {
          values.forEach(value -> addUsage(value, file));
        }
      }
    }
  }

  /** {@code Matcher.group(name)} throws when the pattern has no group of that name. */
  private static String groupOrNull(final Matcher matcher, final String name) {
    return matcher.pattern().namedGroups().containsKey(name) ? matcher.group(name) : null;
  }

  private static void addUsage(final String msgId, final String file) {
    usedMsgIds.computeIfAbsent(msgId, key -> new TreeSet<>()).add(file);
  }

  /** Constant name to every value a scanned class gives it; a name may be reused across classes. */
  private static Map<String, Set<String>> collectConstants(final Iterable<String> sources) {
    final var constants = new HashMap<String, Set<String>>();

    for (final var source : sources) {
      final var matcher = CONSTANT_DEFINITION.matcher(source);

      while (matcher.find()) {
        constants.computeIfAbsent(matcher.group(1), key -> new HashSet<>()).add(matcher.group(2));
      }
    }

    return constants;
  }

  /** File name to source, for the main sources of the backend, {@code libs} and the protocols. */
  private static Map<String, String> readSources() throws IOException {
    final var roots = new ArrayList<Path>();

    roots.add(MODULE_DIR.resolve("src/main/java"));

    for (final var dir : SIBLING_DIRS) {
      try (Stream<Path> walk = Files.walk(dir, 6)) {
        walk.filter(path -> path.endsWith(Path.of("src", "main", "java"))).forEach(roots::add);
      }
    }

    assertThat(roots).as("source roots").hasSizeGreaterThan(10);

    final var sources = new TreeMap<String, String>();

    for (final var root : roots) {
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(path -> path.toString().endsWith(".java"))
            .forEach(path -> sources.put(root.relativize(path).toString(), read(path)));
      }
    }

    return sources;
  }

  private static String read(final Path path) {
    try {
      return Files.readString(path);
    } catch (final IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
