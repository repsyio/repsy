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

import { AsyncPipe } from '@angular/common';
import { Component, Input, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { Observable } from 'rxjs';

import { SecurityScanSupportService } from '../../service/security-scan-support.service';

@Component({
  selector: 'app-security-details-link',
  templateUrl: './security-details-link.component.html',
  imports: [RouterLink, AsyncPipe],
})
export class SecurityDetailsLinkComponent implements OnInit {
  @Input({ required: true }) public repoType: string;

  public isSupported$: Observable<boolean>;

  constructor(private readonly securityScanSupportService: SecurityScanSupportService) {}

  public ngOnInit(): void {
    this.isSupported$ = this.securityScanSupportService.isSupported(this.repoType);
  }
}
