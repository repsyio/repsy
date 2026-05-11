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
import { FormControl, FormGroup, FormsModule, ReactiveFormsModule, Validators } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { finalize } from 'rxjs/operators';

import { environment } from '../../../../../../environments/environment';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import {
  KeyStoreControllerService,
  KeyStoreForm,
  ProtocolRepoControllerService,
  RepoPermissionInfo,
} from '../../../../../../generated/api';
import { SignatureForm } from './dto/signature-form';
import { SignatureItem } from './dto/signature-item';

@Component({
  selector: 'app-signature',
  templateUrl: './signature.component.html',
  standalone: true,
  imports: [CommonModule, ReactiveFormsModule, FormsModule, RouterLink],
})
export class SignatureComponent implements OnInit {
  @Input() public activeRepository: RepoPermissionInfo;
  @Input() public repoType: string;
  public pageNum = 1;
  public pageSize = 5;
  public keyStores: SignatureItem[] = [];
  public wellKnowns: string[] = ['keyserver.ubuntu.com', 'pgp.mit.edu', 'keys.openpgp.org'];
  public keyStoreForm: FormGroup;
  public isSubmitting = false;
  public docsBaseUrl: string;

  constructor(
    private readonly toastService: ToastService,
    private readonly dangerModalService: DangerModalService,
    private readonly keyStoreControllerService: KeyStoreControllerService,
    private readonly protocolRepoControllerService: ProtocolRepoControllerService,
  ) {
    this.docsBaseUrl = environment.docsBase;
    this.initForm();
  }

  ngOnInit(): void {
    this.fetchRepoSettings();
    this.fetchKeyStores();
  }

  private initForm(): void {
    this.keyStoreForm = new FormGroup({
      url: new FormControl('', [
        Validators.required,
        Validators.pattern(/^(?!:\/\/)(?:([a-zA-Z0-9-]+\.)+[a-zA-Z]{2,}|\d{1,3}(?:\.\d{1,3}){3})(:\d{1,5})?$/),
      ]),
    });
  }

  private fetchRepoSettings(): void {
    this.protocolRepoControllerService.getSettings(this.activeRepository.repoName).subscribe({
      next: () => {
        this.keyStoreForm.patchValue({ url: '' });
        this.keyStoreForm.get('url')?.enable();
      },
      error: () => {},
    });
  }

  public createKeyStore(): void {
    if (this.isSubmitting) {
      return;
    }

    this.isSubmitting = true;

    const keyStoreForm: SignatureForm = {
      url: this.keyStoreForm.get('url')?.value,
    };

    this.keyStoreControllerService.createMavenKeyStore(
      this.activeRepository.repoName, keyStoreForm as unknown as KeyStoreForm,
    ).pipe(
      finalize(() => { this.isSubmitting = false; }),
    ).subscribe({
      next: () => {
        this.pageNum = 1;
        this.fetchKeyStores();
        this.keyStoreForm.reset({ active: true });
        this.toastService.show('Key Store URL added', 'success');
      },
      error: () => {},
    });
  }

  public fetchKeyStores(): void {
    this.pageNum = 1;
    this.keyStoreControllerService.listMavenKeyStores(
      { page: 0, size: this.pageSize }, this.activeRepository.repoName,
    ).subscribe({
      next: (r) => {
        this.keyStores = (r.data?.content ?? []) as unknown as SignatureItem[];
      },
      error: () => {},
    });
  }

  public loadMoreKeyStores(): void {
    this.keyStoreControllerService.listMavenKeyStores(
      { page: this.pageNum, size: this.pageSize }, this.activeRepository.repoName,
    ).subscribe({
      next: (r) => {
        const newItems = (r.data?.content ?? []) as unknown as SignatureItem[];
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

  public deleteKeyStore(uuid: string): void {
    this.dangerModalService.show('Delete Key Store', 'Delete', () => {
      this.keyStoreControllerService.deleteMavenKeyStore(uuid, this.activeRepository.repoName).subscribe({
        next: () => {
          this.pageNum = 1;
          this.fetchKeyStores();
          this.toastService.show('Key Store URL deleted', 'success');
        },
        error: () => {},
      });
    });
  }
}
