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

export class ArtifactVersionInfo {
  public uuid: string;
  public name: string;
  public versionName: string;
  public artifactName: string;
  public artifactGroupName: string;
  public artifactVersionName: string;
  public description: string;
  public prefix: string;
  public url: string;
  public organization: string;
  public packaging: string;
  public sourceCodeUrl: string;
  public type: ArtifactVersionType;
  public hasDocuments: boolean;
  public hasSources: boolean;
  public hasModules: boolean;
  public lastUpdatedAt: string;
  public pomFile: string;
  public licenses: VersionLicenseInfo[];
  public developers: VersionDeveloperInfo[];
  public scmUrl: string;
  public signed: boolean;
}

export enum ArtifactVersionType {
  SNAPSHOT,
  RELEASE,
  PLUGIN,
}

export class VersionLicenseInfo {
  public name: string;
  public url: string;
}

export class VersionDeveloperInfo {
  public name: string;
  public email: string;
}
