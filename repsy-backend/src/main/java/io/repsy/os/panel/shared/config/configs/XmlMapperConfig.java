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

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.dataformat.xml.XmlMapper;
import tools.jackson.dataformat.xml.XmlWriteFeature;

/**
 * The XML mapper.
 *
 * <p>{@link XmlMapper} extends the Jackson 3 {@code tools.jackson.databind.ObjectMapper}, which is
 * also the type Spring MVC and the protocol handlers inject. Spring Boot's own {@code JsonMapper}
 * is the {@code @Primary} one, so a plain {@code ObjectMapper} injection point still receives JSON
 * and XML is only ever injected by asking for {@link XmlMapper} explicitly (RPS-901, RPS-955). Do
 * not declare another {@code ObjectMapper} bean here: it would switch Boot's auto-configured JSON
 * mapper off.
 *
 * <p>The mapper is configured to write what the Jackson 2 one did: indented output, ISO-8601 dates,
 * numeric durations, properties in declaration order (Jackson 3 sorts them alphabetically), and a
 * plain empty element for null (Jackson 3 writes {@code xsi:nil}). Jackson 3 also ends indented
 * output with a newline.
 */
@Configuration
public class XmlMapperConfig {

  @Bean
  public @NonNull XmlMapper xmlMapper() {

    return XmlMapper.builder()
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .enable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
        .disable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
        .disable(XmlWriteFeature.WRITE_NULLS_AS_XSI_NIL)
        .enable(SerializationFeature.INDENT_OUTPUT)
        .build();
  }
}
