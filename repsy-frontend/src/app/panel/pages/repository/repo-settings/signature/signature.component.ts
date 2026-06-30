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

import { CommonModule } from '@angular/common';
import { Component, Input, OnInit } from '@angular/core';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';

import { environment } from '../../../../../../environments/environment';
import {
  AllowedKeyserverItem,
  KeyStoreControllerService,
  KeyStoreForm,
  KeyStoreItem,
  RepoPermissionInfo,
} from '../../../../../../generated/api';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { SelectorComponent } from '../../../../shared/components/selector/selector.component';
import { ToastService } from '../../../../shared/components/toast/toast.service';

@Component({
  selector: 'app-signature',
  templateUrl: './signature.component.html',
  standalone: true,
  imports: [CommonModule, SelectorComponent, RouterLink],
})
export class SignatureComponent implements OnInit {
  @Input() public activeRepository: RepoPermissionInfo;
  @Input() public repoType: string;
  public pageNum = 1;
  public pageSize = 5;
  public keyStores: KeyStoreItem[] = [];
  public serverLabels: string[] = [];
  public selectedServerLabel = '';
  public isSubmitting = false;
  public docsBaseUrl: string;

  public readonly wellKnownServers = [
    { host: 'keyserver.ubuntu.com', displayName: 'Ubuntu Keyserver' },
    { host: 'pgp.mit.edu', displayName: 'MIT PGP Public Key Server' },
    { host: 'keys.openpgp.org', displayName: 'OpenPGP Keyserver' },
  ];

  private allowedKeyservers: AllowedKeyserverItem[] = [];

  constructor(
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly keyStoreControllerService: KeyStoreControllerService,
  ) {
    this.docsBaseUrl = environment.docsBase;
  }

  ngOnInit(): void {
    this.fetchKeyStores();
    this.fetchAllowedKeyservers();
  }

  private fetchAllowedKeyservers(): void {
    this.keyStoreControllerService.listAllowedKeyServers().subscribe({
      next: (r) => {
        this.allowedKeyservers = r.data ?? [];
        this.serverLabels = this.allowedKeyservers.map((s) => `${s.displayName} (${s.host})`);
        this.selectedServerLabel = this.serverLabels[0] ?? '';
      },
      error: () => {},
    });
  }

  public createKeyStore(): void {
    if (this.isSubmitting) {
      return;
    }

    const item = this.allowedKeyservers.find((s) => `${s.displayName} (${s.host})` === this.selectedServerLabel);
    if (!item) {
      this.toastService.show('Please select a keyserver', 'error');
      return;
    }

    this.isSubmitting = true;

    const keyStoreForm: KeyStoreForm = { allowedKeyserverId: item.id };

    this.keyStoreControllerService
      .createMavenKeyStore(this.activeRepository.repoName, keyStoreForm)
      .pipe(
        finalize(() => {
          this.isSubmitting = false;
        }),
      )
      .subscribe({
        next: () => {
          this.pageNum = 1;
          this.fetchKeyStores();
          this.toastService.show('Key Store added', 'success');
        },
        error: () => {},
      });
  }

  public fetchKeyStores(): void {
    this.pageNum = 1;
    this.keyStoreControllerService
      .listMavenKeyStores({ page: 0, size: this.pageSize }, this.activeRepository.repoName)
      .subscribe({
        next: (r) => {
          this.keyStores = r.data?.content ?? [];
        },
        error: () => {},
      });
  }

  public loadMoreKeyStores(): void {
    this.keyStoreControllerService
      .listMavenKeyStores({ page: this.pageNum, size: this.pageSize }, this.activeRepository.repoName)
      .subscribe({
        next: (r) => {
          const newItems = r.data?.content ?? [];
          this.keyStores = [...this.keyStores, ...newItems];
          this.pageNum++;
        },
        error: () => {},
      });
  }

  public onScroll(event: Event): void {
    const target = event.target as HTMLElement;
    const bottom = target.scrollHeight === target.scrollTop + target.clientHeight;
    if (bottom) {
      this.loadMoreKeyStores();
    }
  }

  public deleteKeyStore(id: string): void {
    this.dangerModalService.show('Delete Key Store', 'Delete', () => {
      this.keyStoreControllerService.deleteMavenKeyStore(id, this.activeRepository.repoName).subscribe({
        next: () => {
          this.pageNum = 1;
          this.fetchKeyStores();
          this.toastService.show('Key Store deleted', 'success');
        },
        error: () => {},
      });
    });
  }
}
