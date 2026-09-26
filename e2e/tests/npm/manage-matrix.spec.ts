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
 * RPS-1475: the wire permission matrix for npm (README "Manage matrix"). Every operation that
 * changes a package beyond a publish, run by the real `npm` client with every credential:
 * `unpublish` of a version and of a whole package (MANAGE: ADMIN only), `deprecate` and `dist-tag
 * add|rm` (WRITE: ADMIN, USER, read-write token). A refused cell must make the client fail, answer
 * the probed 401 on the wire and leave the package byte for byte as it was.
 */
import { NPM_MANAGE_OPERATIONS } from '../../src/clients/npm-manage.js';
import { registerManageMatrix } from '../../src/scenarios/manage-matrix.js';

registerManageMatrix(NPM_MANAGE_OPERATIONS);
