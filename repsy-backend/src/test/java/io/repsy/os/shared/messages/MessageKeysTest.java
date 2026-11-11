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

import io.repsy.core.test.msgid.MsgIdSourceScanner;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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
 * of errors thrown from services and protocol modules, which {@code ProtocolErrorAdvice} turns into
 * responses.
 *
 * <p>The regular expressions, the source walk and the constant resolution are {@code repsy-core}'s
 * {@link MsgIdSourceScanner} (RPS-2537), shared with Repsy Cloud and Imentis. This class keeps what
 * is Repsy OS's own: the source directories, the call shapes only this code base has, the bundle,
 * the pending list and every assertion.
 *
 * <p>What the scan recognises: an identifier-like string literal or an {@code UPPER_SNAKE} constant
 * that is the first argument of {@code success}/{@code warning}/{@code error} on the response
 * factory, or of a msgId-carrying exception constructor, plus the three shapes below. Ids held in
 * other variables are not seen. A string literal that starts an exception's msgId but is free text
 * or continued by a concatenation, {@code .formatted(} or {@code String.format(} cannot have a
 * bundle entry, so {@link #msgIdsAreFixedIdentifiers()} fails on it (RPS-992, RPS-1127).
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

  /**
   * {@code ErrorResponseService.error(request, status, msgId, ...)}: the panel answers a problem
   * document.
   */
  private static final Pattern ERROR_RESPONSE_MSG_ID =
      Pattern.compile(
          "\\bthis\\s*\\.\\s*(?:errors\\s*\\.\\s*)?error\\(\\s*request\\s*,\\s*[\\w.]+\\s*,\\s*"
              + MsgIdSourceScanner.LITERAL_OR_CONSTANT);

  /**
   * The constant an {@code ProtocolErrorAdvice} handler falls back to, as in {@code
   * exceptionMessage != null ? exceptionMessage : ProtocolErrorCodes.UN_AUTHORIZED}, when the
   * exception carries no message. It reaches {@code resp.error} through a variable, so the scan of
   * the response factory calls misses it.
   */
  private static final Pattern FALLBACK_MSG_ID =
      Pattern.compile(
          "\\?\\s*\\w+\\s*:\\s*(?<constant>(?:ProtocolErrorCodes|ErrorConstants)\\.[A-Z][A-Z0-9_]*)"
              + "\\s*;");

  /**
   * Helpers that pass their last argument on as the msgId of an exception, so the literal never
   * sits next to a {@code new ...Exception(}.
   */
  private static final Pattern FORWARDED_MSG_ID =
      Pattern.compile(
          "\\b(?:stringField|requireGemField)\\((?:[^;()\"]|\"[^\"]*\"|\\([^()]*\\))*,\\s*"
              + "(?:\"(?<literal>\\w+)\"|(?<constant>(?:ProtocolErrorCodes|ErrorConstants)"
              + "\\.[A-Z][A-Z0-9_]*))\\s*\\)");

  /**
   * Exceptions {@code ProtocolErrorAdvice} renders with the exception message as the msgId. The
   * list and the response factory names equal the scanner's defaults and are passed anyway, so a
   * change of those defaults cannot change what this test scans.
   */
  private static final List<String> MSG_ID_EXCEPTION_PREFIXES =
      List.of(
          "ItemNotFound",
          "BadRequest",
          "ItemAlreadyExist",
          "AccessNotAllowed",
          "UnAuthorized",
          "ErrorOccurred",
          "SignatureNotVerified",
          "Mfa",
          "DataExportRequest",
          "SubscriptionLimitReached");

  private static final MsgIdSourceScanner SCANNER =
      MsgIdSourceScanner.builder()
          .exceptionPrefixes(MSG_ID_EXCEPTION_PREFIXES)
          .responseReceivers(List.of("resp", "responseFactory", "restResponseFactory"))
          .extraPattern(ERROR_RESPONSE_MSG_ID)
          .extraPattern(FALLBACK_MSG_ID)
          .extraPattern(FORWARDED_MSG_ID)
          .build();

  /**
   * Error msgIds thrown from exceptions with no bundle entry yet. RPS-958 adds the entries or drops
   * the ids; delete each id here in that change.
   */
  private static final Set<String> PENDING_RPS_958 =
      Set.of(
          "blobNotFound",
          "digestMismatch",
          "fileAlreadyExists",
          "gemNameMissing",
          "gemVersionMissing",
          "goModFileEmpty",
          "goModInvalidModulePath",
          "goModMissingModuleDirective",
          "goModNotFoundInZip",
          "goModuleVersionAlreadyExists",
          "invalidModulePath",
          "invalidPackageVersion",
          "nupkgNotFound",
          "nuspecNotFound",
          "packageVersionAlreadyExists",
          "sha256Mismatch",
          "unknownPath");

  /** Deliberately reserved bundle keys that are not currently emitted by the application. */
  private static final Set<String> RESERVED_KEYS = Set.of();

  private static Properties messages;
  private static Map<String, Set<String>> usedMsgIds;
  private static Set<String> unresolvedConstants;
  private static Map<String, Set<String>> nonIdentifierMsgIds;

  @BeforeAll
  static void scanSources() throws IOException {
    messages = new Properties();

    try (InputStream in = MessageKeysTest.class.getResourceAsStream(BUNDLE)) {
      messages.load(Objects.requireNonNull(in, "messages.properties is not on the classpath"));
    }

    final var roots = new ArrayList<Path>();

    roots.add(MODULE_DIR.resolve("src/main/java"));

    for (final var dir : SIBLING_DIRS) {
      roots.addAll(MsgIdSourceScanner.findSourceRoots(dir, 6));
    }

    assertThat(roots).as("source roots").hasSizeGreaterThan(10);

    final var sources = MsgIdSourceScanner.readSources(roots);
    final var scan = SCANNER.scan(sources, MsgIdSourceScanner.collectConstants(sources.values()));

    usedMsgIds = scan.usedMsgIds();
    unresolvedConstants = scan.unresolvedConstants();
    nonIdentifierMsgIds = scan.nonIdentifierMsgIds();
  }

  @Test
  @DisplayName("every msgId used in code has an entry, or is pending in an allowlist")
  void usedMsgIdsHaveEntries() {
    final var missing = new TreeMap<>(usedMsgIds);

    missing.keySet().removeAll(messages.stringPropertyNames());
    missing.keySet().removeAll(PENDING_RPS_958);

    assertThat(missing)
        .as(
            "msgIds used in code but missing from messages.properties (the API would return the"
                + " raw key as `text`); add an entry for each")
        .isEmpty();
  }

  @Test
  @DisplayName("no exception msgId is free text or built by concatenation")
  void msgIdsAreFixedIdentifiers() {
    assertThat(nonIdentifierMsgIds)
        .as(
            "exception msgIds that are free text or concatenated; they never have a bundle entry,"
                + " so the API returns them as both `msgId` and `text`. Throw a fixed id, add an"
                + " entry for it, and log the variable parts instead")
        .isEmpty();
  }

  @Test
  @DisplayName("the msgId scan flags free text, concatenation, .formatted( and String.format(")
  void nonIdentifierScanFlagsFreeTextMsgIds() {
    assertThat(
            SCANNER.findNonIdentifierMsgIds(
                """
                throw new BadRequestException("Invalid path");
                throw new ItemNotFoundException("no key with Id %s".formatted(id));
                throw new ItemAlreadyExistException("chartAlreadyExists: " + name);
                throw new ItemAlreadyExistException("chartAlreadyExists"
                    + ":" + name);
                throw new AccessNotAllowedException("quote \\" inside", cause);
                throw new MfaException("mfa %s" .formatted(user));
                throw new UnAuthorizedException(
                    "chartAlreadyExists"
                        .formatted(name));
                """))
        .containsExactly(
            "Invalid path",
            "no key with Id %s",
            "chartAlreadyExists: ",
            "chartAlreadyExists",
            "quote \\\" inside",
            "mfa %s",
            "chartAlreadyExists");
  }

  @Test
  @DisplayName("the msgId scan flags a formatting call as the msgId, on one line or several")
  void nonIdentifierScanFlagsFormattingCalls() {
    assertThat(
            SCANNER.findNonIdentifierMsgIds(
                """
                throw new BadRequestException(String.format("Invalid %s", type));
                throw new BadRequestException(
                    String.format("Invalid %s path format: %s", type, path));
                throw new SubscriptionLimitReachedException(MessageFormat.format("x {0}", a));
                throw new DataExportRequestException(String
                    .format("x %s", a));
                """))
        .containsExactly(
            "String.format(", "String.format(", "MessageFormat.format(", "String.format(");
  }

  @Test
  @DisplayName("the msgId scan covers every msgId-carrying exception")
  void nonIdentifierScanCoversEveryExceptionType() {
    final var source =
        MSG_ID_EXCEPTION_PREFIXES.stream()
            .map(type -> "throw new " + type + "Exception(\"free text\");")
            .collect(Collectors.joining("\n"));

    assertThat(SCANNER.findNonIdentifierMsgIds(source)).hasSize(10);
  }

  @Test
  @DisplayName("the msgId scan leaves fixed identifiers, constants and other arguments alone")
  void nonIdentifierScanIgnoresFixedIdentifiers() {
    assertThat(
            SCANNER.findNonIdentifierMsgIds(
                """
                throw new BadRequestException("chartNameMissing");
                throw new ItemNotFoundException(ERR_NOT_FOUND);
                throw new UnAuthorizedException(ProtocolErrorCodes.UN_AUTHORIZED);
                throw new UnAuthorizedException("unAuthorized", Map.of("k", "v"));
                throw new ItemAlreadyExistException("crateVersionAlreadyExists");
                throw new ErrorOccurredException(e);
                throw new IllegalArgumentException("free text is fine here " + name);
                log.warn("no key with Id {}".formatted(id));
                throw new BadRequestException(errorKey);
                """))
        .isEmpty();
  }

  @Test
  @DisplayName("every message bundle key is used or deliberately reserved")
  void bundleKeysAreUsed() {
    final var unused = new TreeSet<>(messages.stringPropertyNames());
    unused.removeAll(usedMsgIds.keySet());
    unused.removeAll(RESERVED_KEYS);

    assertThat(unused)
        .as(
            "message keys not referenced by a recognised response or exception path; remove them"
                + " or add them to RESERVED_KEYS with a comment explaining why they are kept")
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
    assertThat(usedMsgIds)
        .containsKeys(
            "repoNotFound", // literal in an exception
            "unAuthorized", // ErrorConstants constant in an exception
            "validationError", // constant in ProtocolErrorAdvice
            "movedToPath", // constant passed straight to resp.error in ProtocolErrorAdvice
            "unauthorizedRequest", // ProtocolErrorAdvice fallback when the exception has no message
            "chartNameMissing", // BadRequestException in a protocol module
            "gemNameMissing") // forwarded through a helper
        .hasSizeGreaterThan(100);
  }
}
