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
package io.repsy.os.shared.utils;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.repsy.os.shared.error_handling.exceptions.InvalidPagingParameterException;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class PagingOffsetValidatorTest {

  @ParameterizedTest(name = "page={0}, size={1}")
  @MethodSource("nonOverflowingPaging")
  @DisplayName("accepts a page/size product that fits an int, including the exact boundary")
  void acceptsNonOverflowingProducts(final int page, final int size) {
    assertThatCode(() -> PagingOffsetValidator.requireNoOffsetOverflow(page, size))
        .doesNotThrowAnyException();
  }

  static Stream<Arguments> nonOverflowingPaging() {
    return Stream.of(
        Arguments.of(0, 1),
        Arguments.of(3, 100),
        Arguments.of(1000000, 100),
        // page * size == Integer.MAX_VALUE exactly: the product does not exceed it.
        Arguments.of(Integer.MAX_VALUE, 1));
  }

  @ParameterizedTest(name = "page={0}, size={1}")
  @MethodSource("overflowingPaging")
  @DisplayName("rejects a page/size product that overflows int, naming page (RPS-1150)")
  void rejectsOverflowingProducts(final int page, final int size) {
    assertThatThrownBy(() -> PagingOffsetValidator.requireNoOffsetOverflow(page, size))
        .isInstanceOf(InvalidPagingParameterException.class)
        .extracting(e -> ((InvalidPagingParameterException) e).getParameterNames())
        .isEqualTo("page");
  }

  static Stream<Arguments> overflowingPaging() {
    return Stream.of(
        // The exact reproduction from RPS-1150.
        Arguments.of(Integer.MAX_VALUE, 100),
        // Just one past the page*size==Integer.MAX_VALUE boundary for size=100.
        Arguments.of(21474837, 100));
  }
}
