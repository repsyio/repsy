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
package io.repsy.core.events;

import org.jspecify.annotations.NonNull;
import org.springframework.core.ResolvableType;
import org.springframework.core.ResolvableTypeProvider;

public record UserCreatedEvent<T>(@NonNull T userId, @NonNull String username)
    implements ResolvableTypeProvider {

  @Override
  public @NonNull ResolvableType getResolvableType() {
    return ResolvableType.forClassWithGenerics(UserCreatedEvent.class, this.userId.getClass());
  }
}
