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

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Guards against the XML mapper being injected where a JSON mapper is expected: {@code XmlMapper}
 * is a Jackson 2 {@code ObjectMapper}, so a plain injection point used to receive it and serialize
 * to XML (RPS-901, RPS-955).
 */
@DisplayName("Jackson2MapperConfig")
class Jackson2MapperConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withUserConfiguration(Jackson2MapperConfig.class, Consumer.class);

  @Test
  @DisplayName("injects a JSON mapper into a plain ObjectMapper injection point")
  void plainObjectMapperSerializesToJson() {
    this.runner.run(
        context -> {
          final var consumer = context.getBean(Consumer.class);

          assertThat(consumer.objectMapper).isNotInstanceOf(XmlMapper.class);
          assertThat(consumer.objectMapper.writeValueAsString(Map.of("name", "repsy")))
              .isEqualTo("{\"name\":\"repsy\"}");
        });
  }

  @Test
  @DisplayName("resolves the default ObjectMapper bean to the JSON mapper")
  void defaultObjectMapperBeanIsJson() {
    this.runner.run(
        context ->
            assertThat(context.getBean(ObjectMapper.class).writeValueAsString(Map.of("a", 1)))
                .isEqualTo("{\"a\":1}"));
  }

  @Test
  @DisplayName("writes dates as ISO-8601 strings, not timestamps")
  void writesDatesAsStrings() {
    this.runner.run(
        context ->
            assertThat(
                    context
                        .getBean(ObjectMapper.class)
                        .writeValueAsString(Map.of("at", Instant.parse("2026-09-19T10:15:30Z"))))
                .isEqualTo("{\"at\":\"2026-09-19T10:15:30Z\"}"));
  }

  @Test
  @DisplayName("still hands out the XML mapper to an XmlMapper injection point")
  void xmlMapperIsStillInjectableByType() {
    this.runner.run(
        context ->
            assertThat(context.getBean(XmlMapper.class).writeValueAsString(Map.of("name", "repsy")))
                .startsWith("<")
                .contains("<name>repsy</name>"));
  }

  static class Consumer {

    // Field injection on purpose: it is the shape of the mistake this test guards against.
    @Autowired ObjectMapper objectMapper;
  }
}
