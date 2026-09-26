///
/// Copyright 2026 the original author or authors.
///
/// Licensed under the Apache License, Version 2.0 (the "License");
/// you may not use this file except in compliance with the License.
/// You may obtain a copy of the License at
///
///      https://www.apache.org/licenses/LICENSE-2.0
///
/// Unless required by applicable law or agreed to in writing, software
/// distributed under the License is distributed on an "AS IS" BASIS,
/// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
/// See the License for the specific language governing permissions and
/// limitations under the License.
///

/**
 * RPS-1475: the wire permission matrix for NuGet (README "Manage matrix"). `unlist` and `relist` of
 * a version, both WRITE (ADMIN, USER, read-write token), with every credential. There is no real
 * client operation for them here (`dotnet nuget delete` is RPS-1486): the cells are raw requests,
 * and a refused cell must answer 401 and leave the registration exactly as it was.
 */
import { NUGET_MANAGE_OPERATIONS } from '../../src/clients/nuget-manage.js';
import { registerManageMatrix } from '../../src/scenarios/manage-matrix.js';

registerManageMatrix(NUGET_MANAGE_OPERATIONS);
