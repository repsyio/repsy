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

import { ReleaseClassifierInfo } from './release-classifier-info';
import { ReleaseProjectUrlInfo } from './release-project-url-info';

export class ReleaseInfo {
  public uuid: string;
  public packageName: string;
  public stableVersion: string;
  public version: string;
  public finalRelease: boolean;
  public preRelease: boolean;
  public postRelease: boolean;
  public devRelease: boolean;
  public requiresPython: string;
  public summary: string;
  public homePage: string;
  public author: string;
  public authorEmail: string;
  public license: string;
  public description: string;
  public descriptionContentType: string;
  public createdAt: string;
  public classifiers: ReleaseClassifierInfo[];
  public projectUrls: ReleaseProjectUrlInfo[];
}
