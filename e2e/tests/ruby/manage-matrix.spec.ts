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
 * RPS-1475: the wire permission matrix for Ruby (README "Manage matrix"). `gem yank` of a version,
 * run by the real `gem` client with every credential: WRITE (ADMIN, USER, read-write token, RPS-1317).
 * A refused cell must make the client fail, answer the probed 401 on the wire and leave the compact
 * index and every `.gem` as they were.
 */
import { RUBY_MANAGE_OPERATIONS } from '../../src/clients/ruby-manage.js';
import { registerManageMatrix } from '../../src/scenarios/manage-matrix.js';

registerManageMatrix(RUBY_MANAGE_OPERATIONS);
