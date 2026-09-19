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
package io.repsy.protocols.shared.utils;

import static org.assertj.core.api.Assertions.assertThat;

import io.repsy.libs.protocol.router.ProtocolContext;
import io.repsy.libs.storage.core.dtos.BaseUsages;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProtocolContextUtils.addUsages")
class ProtocolContextUtilsTest {

  private static long usageOf(final ProtocolContext context) {
    return context.<BaseUsages>getProperty("usages").getDiskUsage();
  }

  @Test
  @DisplayName("puts the usage on a context that has none yet")
  void setsUsageOnEmptyContext() {
    final var context = new ProtocolContext();

    ProtocolContextUtils.addUsages(context, BaseUsages.ofDisk(120));

    assertThat(usageOf(context)).isEqualTo(120);
  }

  @Test
  @DisplayName("adds to what the request already reported instead of replacing it")
  void addsToExistingUsage() {
    final var context = new ProtocolContext();
    context.addProperty("usages", BaseUsages.ofDisk(120));

    ProtocolContextUtils.addUsages(context, BaseUsages.ofDisk(30));

    assertThat(usageOf(context)).isEqualTo(150);
  }

  @Test
  @DisplayName("nets a refund against a charge made earlier in the same request")
  void netsRefundAgainstCharge() {
    final var context = new ProtocolContext();
    ProtocolContextUtils.addUsages(context, BaseUsages.ofDisk(120));

    ProtocolContextUtils.addUsages(context, BaseUsages.ofDisk(-120));

    assertThat(usageOf(context)).isZero();
  }

  @Test
  @DisplayName("keeps a refund as negative usage when nothing was charged in the request")
  void keepsRefundWithoutCharge() {
    final var context = new ProtocolContext();

    ProtocolContextUtils.addUsages(context, BaseUsages.ofDisk(-75));

    assertThat(usageOf(context)).isEqualTo(-75);
  }
}
