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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * The Jackson 2 mappers.
 *
 * <p>{@link XmlMapper} extends the Jackson 2 {@link ObjectMapper}, and Spring Boot 4 does not
 * auto-configure a Jackson 2 JSON mapper, so on its own the XML mapper would be the only candidate
 * for a plain {@code com.fasterxml.jackson.databind.ObjectMapper} injection point and silently
 * serialize to XML (RPS-901). The {@link Primary} JSON mapper takes that role, so XML is only ever
 * injected by asking for {@link XmlMapper} explicitly.
 *
 * <p>The mappers Spring MVC and the protocol handlers use are the Jackson 3 {@code
 * tools.jackson.databind.ObjectMapper} and are not affected.
 */
@Configuration
public class Jackson2MapperConfig {

  @Bean
  @Primary
  public @NonNull ObjectMapper jsonObjectMapper() {

    return JsonMapper.builder()
        .addModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .build();
  }

  @Bean
  public @NonNull XmlMapper xmlMapper() {

    final var mapper = new XmlMapper();

    mapper.registerModule(new JavaTimeModule());
    mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    mapper.enable(SerializationFeature.INDENT_OUTPUT);

    return mapper;
  }
}
