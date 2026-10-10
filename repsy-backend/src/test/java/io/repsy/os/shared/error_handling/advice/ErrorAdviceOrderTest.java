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
package io.repsy.os.shared.error_handling.advice;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.annotation.Order;
import org.springframework.web.bind.annotation.ExceptionHandler;

/**
 * RPS-2063: splitting the former single {@code ErrorHandler} is only the same answer if no advice
 * handles a supertype of what the other handles (Spring picks the first advice, by order, that has
 * any matching handler, not the closest type across advices), apart from the {@code Throwable}
 * fallback, which therefore must be in the last advice.
 */
class ErrorAdviceOrderTest {

  private static final List<Class<?>> ADVICES =
      List.of(PanelProblemDetailAdvice.class, ProtocolErrorAdvice.class);

  @Test
  @DisplayName("no handled type is a supertype of a type another advice handles, except Throwable")
  void noAdviceShadowsAnother() {
    for (final var advice : ADVICES) {
      for (final var type : handledTypes(advice)) {
        if (type == Throwable.class) {
          continue;
        }

        for (final var other : ADVICES) {
          if (other == advice) {
            continue;
          }

          for (final var otherType : handledTypes(other)) {
            assertThat(type.isAssignableFrom(otherType))
                .as(
                    "%s handles %s, a supertype of %s handled by %s",
                    advice.getSimpleName(),
                    type.getSimpleName(),
                    otherType.getSimpleName(),
                    other.getSimpleName())
                .isFalse();
          }
        }
      }
    }
  }

  @Test
  @DisplayName("the advice with the Throwable fallback is ordered after every other advice")
  void fallbackAdviceIsLast() {
    final var fallbackOrder = ProtocolErrorAdvice.class.getAnnotation(Order.class).value();

    assertThat(handledTypes(ProtocolErrorAdvice.class)).contains(Throwable.class);

    for (final var advice : ADVICES) {
      if (advice != ProtocolErrorAdvice.class) {
        assertThat(handledTypes(advice)).doesNotContain(Throwable.class);
        assertThat(advice.getAnnotation(Order.class).value()).isLessThan(fallbackOrder);
      }
    }
  }

  @Test
  @DisplayName("no exception type is handled by two advices")
  void handledTypesAreDisjoint() {
    final var seen = new ArrayList<Class<? extends Throwable>>();

    for (final var advice : ADVICES) {
      for (final var type : handledTypes(advice)) {
        assertThat(seen).as("%s handled twice", type.getSimpleName()).doesNotContain(type);
        seen.add(type);
      }
    }
  }

  private static List<Class<? extends Throwable>> handledTypes(final Class<?> advice) {
    final var types = new ArrayList<Class<? extends Throwable>>();

    for (final Method method : advice.getDeclaredMethods()) {
      final var handler = method.getAnnotation(ExceptionHandler.class);

      if (handler != null) {
        types.addAll(List.of(handler.value()));
      }
    }

    return types;
  }
}
