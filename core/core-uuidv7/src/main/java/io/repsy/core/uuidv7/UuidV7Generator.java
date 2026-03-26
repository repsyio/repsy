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
package io.repsy.core.uuidv7;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.EnumSet;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.generator.BeforeExecutionGenerator;
import org.hibernate.generator.EventType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

public class UuidV7Generator implements BeforeExecutionGenerator {

  @Override
  public @NonNull UUID generate(
      final @NonNull SharedSessionContractImplementor session,
      final @NonNull Object owner,
      final @Nullable Object currentValue,
      final @NonNull EventType eventType) {
    return UuidCreator.getTimeOrderedEpoch();
  }

  @Override
  public @NonNull EnumSet<EventType> getEventTypes() {
    return EnumSet.of(EventType.INSERT);
  }
}
