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
package io.repsy.protocols.oci.handlers;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.protocols.shared.utils.ProtocolContextUtils;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.experimental.UtilityClass;

/**
 * Matches the relative path of a routed request again inside {@code handle}. The route already
 * tested it, so an empty answer is unreachable; the handlers answer it with the 500 they always
 * did.
 */
@UtilityClass
final class OciPathMatches {

  static Optional<Matcher> match(final Pattern pattern, final ProtocolContext context) {
    final var matcher = pattern.matcher(ProtocolContextUtils.getRelativePath(context).getPath());

    return matcher.matches() ? Optional.of(matcher) : Optional.empty();
  }
}
