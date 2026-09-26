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
 * RPS-1475: the wire permission matrix for Docker (README "Manage matrix"). `crane delete` of a
 * manifest by digest and of a tag with every credential (MANAGE: ADMIN only, RPS-1216, RPS-1424): a USER
 * account and a deploy token, read-write or not, are refused with 401 on the DELETE and the image stays.
 */
import { DOCKER_MANAGE_OPERATIONS } from '../../src/clients/docker-manage.js';
import { registerManageMatrix } from '../../src/scenarios/manage-matrix.js';

registerManageMatrix(DOCKER_MANAGE_OPERATIONS);
