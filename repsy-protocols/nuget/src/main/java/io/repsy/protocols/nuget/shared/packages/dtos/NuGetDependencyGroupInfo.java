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
package io.repsy.protocols.nuget.shared.packages.dtos;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One {@code <group targetFramework="...">} of a nuspec. A group can declare no dependency at all
 * ({@code <group targetFramework="net10.0"/>}): that is a statement that the framework needs
 * nothing, so it is kept and not merged into another group (RPS-1555). A group without a target
 * framework applies to every framework.
 */
public record NuGetDependencyGroupInfo(
    @Nullable String targetFramework, List<NuGetDependencyInfo> dependencies) {}
