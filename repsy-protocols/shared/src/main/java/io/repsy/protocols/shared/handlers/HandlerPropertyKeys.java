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
package io.repsy.protocols.shared.handlers;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/**
 * The keys of {@code ProtocolMethodHandler#getProperties()} that the pre- and post-processors read.
 * A typo in a key silently changes a permission, so the handlers (through {@link HandlerRoute}) and
 * the processors share these constants instead of repeating the literals.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class HandlerPropertyKeys {

  /** The {@code Permission} the caller needs on the repository. */
  public static final String PERMISSION = "permission";

  /** {@code true} when the request changes the repository (decides write access and quotas). */
  public static final String WRITE_OPERATION = "writeOperation";

  /** {@code true} when the auth pre-processor must not run for the request. */
  public static final String SKIP_PRE_PROCESSOR = "skipPreProcessor";

  /** {@code true} when the header pre-processor must not run for the request. */
  public static final String SKIP_HEADER_PRE_PROCESSOR = "skipHeaderPreProcessor";

  /** {@code true} when the request must not be counted by the usage post-processor. */
  public static final String SKIP_USAGE_POST_PROCESSOR = "skipUsagePostProcessor";

  /** The protocol operation a handler names for itself (for example Go {@code download}). */
  public static final String METHOD = "method";
}
