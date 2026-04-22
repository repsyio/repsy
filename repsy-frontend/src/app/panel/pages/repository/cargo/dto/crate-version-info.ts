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
export class CrateVersionInfo {
  public crateId: string;
  public name: string;
  public version: string;
  public readme: string;
  public license: string;
  public license_file: string;
  public documentation: string;
  public edition: string;
  public hasLib: boolean;
  public rust_version: string;
  public deps: CrateDependencyInfo[];
  public downloads: number;
  public created_at: Date;
}

export class CrateDependencyInfo {
  public name: string;
  public req: string;
  public features: string[];
  public optional: boolean;
  public default_features: boolean;
  public target: string;
  public kind: string;
  public registry: string;
  public package: string;
}
