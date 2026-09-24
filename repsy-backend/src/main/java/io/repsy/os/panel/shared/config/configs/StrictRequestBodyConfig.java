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
package io.repsy.os.panel.shared.config.configs;

import io.repsy.os.generated.model.DeployTokenForm;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.JacksonException;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.deser.DeserializationProblemHandler;
import tools.jackson.databind.exc.UnrecognizedPropertyException;

/**
 * Request bodies whose unknown properties are an error instead of being skipped.
 *
 * <p>The Spring Boot mapper ignores unknown JSON properties, which is the right default for a
 * tolerant API. It is wrong for a body where a renamed key changes what the request means: a client
 * still sending the pre-camelCase {@code read_only} to create a deploy token would get a read-write
 * token without a word (RPS-1269). Such a body answers 400 {@code validationError} instead.
 */
@Configuration
public class StrictRequestBodyConfig {

  /** The bodies that reject an unknown property. */
  private static final List<Class<?>> STRICT_BODIES = List.of(DeployTokenForm.class);

  @Bean
  public @NonNull JsonMapperBuilderCustomizer strictRequestBodyCustomizer() {

    return builder ->
        builder.addHandler(
            new DeserializationProblemHandler() {
              @Override
              public boolean handleUnknownProperty(
                  final DeserializationContext ctxt,
                  final JsonParser p,
                  final ValueDeserializer<?> deserializer,
                  final Object beanOrClass,
                  final String propertyName)
                  throws JacksonException {

                final Class<?> type =
                    beanOrClass instanceof Class<?> clazz ? clazz : beanOrClass.getClass();

                if (!STRICT_BODIES.contains(type)) {
                  return false;
                }

                final Collection<Object> known = deserializer.getKnownPropertyNames();

                throw UnrecognizedPropertyException.from(p, beanOrClass, propertyName, known);
              }
            });
  }
}
