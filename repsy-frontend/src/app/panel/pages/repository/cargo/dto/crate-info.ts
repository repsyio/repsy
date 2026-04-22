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
export class CrateInfo {
  public id: string;
  public name: string;
  public original_name: string;
  public max_version: string;
  public total_downloads: number;
  public description: string;
  public homepage: string;
  public repository: string;
  public authors: string[];
  public keywords: string[];
  public categories: string[];
}
