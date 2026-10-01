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

import io.repsy.protocols.nuget.shared.packages.dtos.NuGetDependencyInfo;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.dataformat.xml.XmlMapper;

/**
 * Guards against the XML mapper being injected where a JSON mapper is expected: {@code XmlMapper}
 * is a Jackson 3 {@code ObjectMapper}, so a plain injection point could receive it and serialize to
 * XML (RPS-901, RPS-955). Also pins what both mappers write over the wire, which the Jackson 2 to
 * Jackson 3 move (RPS-1811) must not change.
 */
@DisplayName("XmlMapperConfig")
class XmlMapperConfigTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
          .withUserConfiguration(XmlMapperConfig.class, Consumer.class);

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
  @DisplayName("JSON wire format of Boot's mapper: ISO-8601 dates and durations, order, nulls kept")
  void jsonWireFormat() {
    this.runner.run(
        context ->
            assertThat(context.getBean(ObjectMapper.class).writeValueAsString(wireSample()))
                .isEqualTo(
                    "{\"b\":1,\"a\":[\"x\",\"y\"],\"inst\":\"2026-09-19T10:15:30.123456Z\","
                        + "\"date\":\"2026-09-19\",\"zdt\":\"2026-09-19T10:15:30+02:00\","
                        + "\"dur\":\"PT1M30S\",\"nul\":null}"));
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

  @Test
  @DisplayName("XML wire format: indented, ISO-8601 dates, declaration order, no xsi:nil")
  void xmlWireFormat() {
    this.runner.run(
        context -> {
          final var xml = context.getBean(XmlMapper.class);

          assertThat(xml.writeValueAsString(wireSample()).stripTrailing())
              .isEqualTo(
                  """
                  <LinkedHashMap>
                    <b>1</b>
                    <a>x</a>
                    <a>y</a>
                    <inst>2026-09-19T10:15:30.123456Z</inst>
                    <date>2026-09-19</date>
                    <zdt>2026-09-19T10:15:30+02:00</zdt>
                    <dur>90.000000000</dur>
                    <nul/>
                  </LinkedHashMap>
                  """
                      .stripTrailing());

          final var dependencies =
              new ArrayList<>(
                  List.of(
                      new NuGetDependencyInfo("Newtonsoft.Json", "[13.0.1, )", "net6.0"),
                      new NuGetDependencyInfo("Legacy.Flat", "", null)));

          assertThat(xml.writeValueAsString(dependencies).stripTrailing())
              .isEqualTo(
                  """
                  <ArrayList>
                    <item>
                      <packageId>Newtonsoft.Json</packageId>
                      <versionRange>[13.0.1, )</versionRange>
                      <targetFramework>net6.0</targetFramework>
                    </item>
                    <item>
                      <packageId>Legacy.Flat</packageId>
                      <versionRange></versionRange>
                      <targetFramework/>
                    </item>
                  </ArrayList>
                  """
                      .stripTrailing());
        });
  }

  private static Map<String, Object> wireSample() {
    final var sample = new LinkedHashMap<String, Object>();
    sample.put("b", 1);
    sample.put("a", List.of("x", "y"));
    sample.put("inst", Instant.parse("2026-09-19T10:15:30.123456Z"));
    sample.put("date", LocalDate.of(2026, 9, 19));
    sample.put("zdt", ZonedDateTime.parse("2026-09-19T10:15:30+02:00[Europe/Paris]"));
    sample.put("dur", Duration.ofSeconds(90));
    sample.put("nul", null);

    return sample;
  }

  static class Consumer {

    // Field injection on purpose: it is the shape of the mistake this test guards against.
    @Autowired ObjectMapper objectMapper;
  }
}
