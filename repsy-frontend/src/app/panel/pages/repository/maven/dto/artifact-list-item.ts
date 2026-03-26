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

export class ArtifactListItem {
  public groupName: string;
  public artifactName: string;
  public latest: string;
  public size: number;
  public name: string;
  public directory: boolean;
  public packaging: string;
  public createdAt: Date;
  public versionName: string;
  public lastUpdatedAt: string;
}
