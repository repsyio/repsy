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
package io.repsy.os.shared.token.configs.validators;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class InstantRangeValidator implements ConstraintValidator<ValidExpirationDate, Instant> {

  private static final Duration MAX_DATE_RANGE = Duration.of(365, ChronoUnit.DAYS);

  @Override
  public boolean isValid(
      final @Nullable Instant tokenCandidate, final @NonNull ConstraintValidatorContext context) {

    if (tokenCandidate == null) {
      return true;
    }

    final var now = Instant.now();
    final var maxDate = now.plus(MAX_DATE_RANGE);

    return !tokenCandidate.isBefore(now) && !tokenCandidate.isAfter(maxDate);
  }
}
