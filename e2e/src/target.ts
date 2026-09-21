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

import { env, type RepsyTarget } from './env.js';

export interface TargetCapabilities {
  /** Whether the harness started this stack and may restart or reconfigure it. */
  ownsStack: boolean;
  /** Whether AUTH_THROTTLE_* can be raised for this run, so negative-auth scenarios can run freely. */
  canTuneThrottle: boolean;
  /** Whether this is a shared instance the harness must leave exactly as it found it. */
  isRemote: boolean;
}

const CAPABILITIES: Record<RepsyTarget, TargetCapabilities> = {
  local: { ownsStack: true, canTuneThrottle: true, isRemote: false },
  ci: { ownsStack: true, canTuneThrottle: true, isRemote: false },
  remote: { ownsStack: false, canTuneThrottle: false, isRemote: true },
};

export function capabilitiesFor(target: RepsyTarget): TargetCapabilities {
  return CAPABILITIES[target];
}

export const target: TargetCapabilities = capabilitiesFor(env.target);
