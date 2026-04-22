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
package io.repsy.os.shared.usage.controllers;

import io.repsy.core.response.dtos.RestResponse;
import io.repsy.core.response.services.RestResponseFactory;
import io.repsy.libs.multiport.annotations.RestApiPort;
import io.repsy.os.shared.usage.dtos.TotalUsageInfo;
import io.repsy.os.shared.usage.services.UsageService;
import io.repsy.os.shared.utils.MultiPortNames;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestApiPort(MultiPortNames.PORT_API)
@RestController
@RequestMapping("/api/usages")
@RequiredArgsConstructor
public class UsageController {

  private final @NonNull RestResponseFactory responseFactory;
  private final @NonNull UsageService usageService;

  @GetMapping
  public @NonNull RestResponse<TotalUsageInfo> getTotalUsage() {

    final var totalUsageInfo = this.usageService.getTotalUsageInfo();

    return this.responseFactory.success("usageFetched", totalUsageInfo);
  }
}
