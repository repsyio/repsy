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
 * RPS-1475: the wire permission matrix for Cargo (README "Manage matrix"). Every operation that
 * changes a crate beyond a publish, run by the real `cargo` client with every credential: `yank` and
 * `yank --undo`, `owner --add` and `owner --remove`, all WRITE (ADMIN, USER, read-write token). A
 * refused cell must make the client fail, answer the probed 401 on the wire and leave the crate as
 * it was. (Owner changes are a no-op on Repsy, which has no ownership model finer than the
 * repository: see `clients/cargo-manage.ts`.)
 */
import { CARGO_MANAGE_OPERATIONS } from '../../src/clients/cargo-manage.js';
import { registerManageMatrix } from '../../src/scenarios/manage-matrix.js';

registerManageMatrix(CARGO_MANAGE_OPERATIONS);
