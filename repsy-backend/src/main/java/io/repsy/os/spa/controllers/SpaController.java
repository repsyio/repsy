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
package io.repsy.os.spa.controllers;

import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.shared.utils.MultiPortNames;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

@RestApiPort(MultiPortNames.PORT_API)
@Controller
public class SpaController {

  @GetMapping(
      value = {
        "/",
        "/{path:^(?!api|assets|favicon\\.ico)[^\\.]*$}",
        "/{path:^(?!api|assets|favicon\\.ico)[^\\.]*$}/**"
      })
  public @NonNull String forward(@PathVariable(required = false) final @NonNull String path) {
    return "forward:/index.html";
  }
}
