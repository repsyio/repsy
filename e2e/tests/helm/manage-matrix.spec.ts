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
 * RPS-1475: the wire permission matrix for Helm (README "Manage matrix"). The classic chart DELETE
 * (MANAGE: ADMIN only, a USER account and a deploy token are refused with 401, RPS-1424; no Helm command
 * sends it, so the request is raw) and the OCI manifest DELETE, which Helm's OCI protocol has no route
 * for (NO_ROUTE: every credential gets 404). A refused cell must leave the chart served byte for byte.
 */
import { HELM_MANAGE_OPERATIONS } from '../../src/clients/helm-manage.js';
import { registerManageMatrix } from '../../src/scenarios/manage-matrix.js';

registerManageMatrix(HELM_MANAGE_OPERATIONS);
