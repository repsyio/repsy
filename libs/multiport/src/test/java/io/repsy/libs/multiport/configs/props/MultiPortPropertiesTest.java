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
package io.repsy.libs.multiport.configs.props;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class MultiPortPropertiesTest {

  private static final int PROTOCOL_PORT = 9090;
  private static final int OTHER_PORT = 9091;

  private MultiPortProperties properties;

  @BeforeEach
  void setUp() {
    this.properties = new MultiPortProperties();
    this.properties.setPorts(Map.of("protocol", PROTOCOL_PORT, "other", OTHER_PORT));
  }

  @Test
  void mainPortShouldDefaultTo8080() {

    assertThat(this.properties.getMainPort()).isEqualTo("8080");
  }

  @Test
  void getPortForShouldReturnTheConfiguredPort() {

    assertThat(this.properties.getPortFor("protocol")).isEqualTo(PROTOCOL_PORT);
  }

  @Test
  void getAdditionalPortsShouldLeaveOutThePortsThatEqualTheMainPort() {

    this.properties.setMainPort(String.valueOf(OTHER_PORT));

    assertThat(this.properties.getAdditionalPorts()).containsExactly(Map.entry("protocol", 9090));
  }

  @Test
  void validatePortsShouldAcceptPortsThatDifferFromTheMainPort() {

    assertThatCode(this.properties::validatePorts).doesNotThrowAnyException();
  }

  @Test
  void validatePortsShouldRejectAPortThatEqualsTheMainPort() {

    this.properties.setMainPort(String.valueOf(PROTOCOL_PORT));

    assertThatThrownBy(this.properties::validatePorts)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot equal main-port (9090)");
  }

  @Test
  void validatePortsShouldAcceptAnAliasForAKnownPortOrAnEmptyOne() {

    this.properties.setPortAliases(Map.of(8443, "protocol", 8444, ""));

    assertThatCode(this.properties::validatePorts).doesNotThrowAnyException();
  }

  @Test
  void validatePortsShouldRejectAnAliasForAnUnknownPort() {

    this.properties.setPortAliases(Map.of(8443, "missing"));

    assertThatThrownBy(this.properties::validatePorts)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown port name 'missing'");
  }
}
