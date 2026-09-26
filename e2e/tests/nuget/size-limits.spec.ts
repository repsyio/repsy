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

/**
 * RPS-1482: a real `dotnet nuget push` of a package over the upload limit, on a stack started with the limits overlay
 * (`./run.sh local up --limits`, README.md "Size-limit leg"). Registered by `registerSizeLimitSpecs`
 * (`src/scenarios/size-limits.ts`); the push itself is `clients/oversize.ts`.
 */
import { RepoType } from '../../src/api/panel-api.js';
import { nugetAdapter } from '../../src/clients/nuget.js';
import { pushNuget } from '../../src/clients/oversize.js';
import { registerSizeLimitSpecs } from '../../src/scenarios/size-limits.js';

registerSizeLimitSpecs({
  protocol: 'nuget',
  client: 'dotnet nuget push',
  repoType: RepoType.NUGET,
  adapter: nugetAdapter,
  push: pushNuget,
  clientMessage: /error: Response status code does not indicate success: 413/,
});
