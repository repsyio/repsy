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

import { PackageDistributionTagListItem } from './package-distribution-tag-list-item';
import { PackageKeywordListItem } from './package-keyword-list-item';
import { PackageMaintainerListItem } from './package-maintainer-list-item';

export class PackageVersionInfo {
  public versionUuid: string;
  public scopeName?: string;
  public packageName: string;
  public fullName: string;
  public versionName: string;
  public authorName?: string;
  public authorEmail?: string;
  public authorUrl?: string;
  public bugsUrl?: string;
  public bugsEmail?: string;
  public description?: string;
  public homepage?: string;
  public license?: string;
  public repositoryType?: string;
  public repositoryUrl?: string;
  public deprecated: boolean;
  public deprecationMessage?: string;
  public readme?: string;
  public keywords: PackageKeywordListItem[];
  public maintainers: PackageMaintainerListItem[];
  public distributionTags: PackageDistributionTagListItem[];
  public deleted: boolean;
  public createdAt: Date;
}
