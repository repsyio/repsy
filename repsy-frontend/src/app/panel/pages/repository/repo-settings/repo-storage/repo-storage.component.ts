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

import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { ReactiveFormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { ToastService } from '../../../../shared/components/toast/toast.service';
import { ProtocolRepoControllerService, RepoInfo, RepoUsageInfo } from '../../../../../../generated/api';

@Component({
  selector: 'app-repo-storage',
  templateUrl: './repo-storage.component.html',
  standalone: true,
  imports: [ReactiveFormsModule, RouterLink],
})
export class RepoStorageComponent implements OnInit {
  @Input() public repoType: string;
  @Input() public repoName: string;
  @Output() public fetch = new EventEmitter<void>();

  public usage: RepoUsageInfo;

  constructor(
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
    private readonly toastService: ToastService,
  ) {}

  ngOnInit(): void {
    this.fetchRepoUsage();
  }

  fetchRepoUsage() {
    this.protocolRepoControllerService.getUsage({} as RepoInfo, this.repoName).subscribe({
      next: (r) => { this.usage = r.data!; },
      error: () => {},
    });
  }
}
