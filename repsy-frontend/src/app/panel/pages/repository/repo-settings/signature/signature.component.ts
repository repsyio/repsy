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

import { environment } from '../../../../../../environments/environment';
import { DangerModalService } from '../../../../shared/components/modals/danger-modal/danger-modal.service';
import { ToastService } from '../../../../shared/components/toast/toast.service';
import { PagedData } from '../../../../shared/dto/paged-data';
import { RepoPermissionInfo } from '../../../../shared/dto/repo/repo-permission-info';
import { RepoType } from '../../../../shared/dto/repo/repo-type';
import { DockerService } from '../../docker/service/docker.service';
import { MavenService } from '../../maven/service/maven.service';
import { NpmService } from '../../npm/service/npm.service';
import { RepositorySettingsInfo } from '../../pypi/dto/repository-settings-info';
import { PypiService } from '../../pypi/service/pypi.service';
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
    private readonly mavenService: MavenService,
    private readonly npmService: NpmService,
    private readonly pypiService: PypiService,
    private readonly dockerService: DockerService,
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
    this.getRepoSettingsService()
      .then(() => {
        this.keyStoreForm.patchValue({
          url: '',
        });
        this.keyStoreForm.get('url')?.enable();
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
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

    this.createKeyStoreService(keyStoreForm)
      .then(() => {
        this.pageNum = 1;
        this.fetchKeyStores();
        this.keyStoreForm.reset({ active: true });
        this.toastService.show('Key Store URL added', 'success');
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.isSubmitting = false;
      });
  }

  public fetchKeyStores(): void {
    this.pageNum = 1;
    this.fetchKeyStoreService(0, this.pageSize)
      .then((data) => {
        this.keyStores = data.content;
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      });
  }

  public loadMoreKeyStores(): void {
    this.fetchKeyStoreService(this.pageNum, this.pageSize)
      .then((pageData) => {
        const newItems: SignatureItem[] = pageData.content;
        this.keyStores = [...this.keyStores, ...newItems];
        this.pageNum++;
      })
      .catch((err: string) => {
        this.toastService.show(err, 'error');
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
      this.deleteKeyStoreService(uuid)
        .then(() => {
          this.pageNum = 1;
          this.fetchKeyStores();
          this.toastService.show('Key Store URL deleted', 'success');
        })
        .catch((err: string) => {
          this.toastService.show(err, 'error');
        });
    });
  }

  // Service methods based on repoType
  private createKeyStoreService(form: SignatureForm) {
    switch (this.repoType) {
      case RepoType.MAVEN: {
        return this.mavenService.createKeyStore(form);
      }
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  private deleteKeyStoreService(uuid: string): Promise<void> {
    switch (this.repoType) {
      case RepoType.MAVEN: {
        return this.mavenService.deleteKeyStore(uuid);
      }
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  private fetchKeyStoreService(pageIndex: number, pageSize: number): Promise<PagedData<SignatureItem>> {
    switch (this.repoType) {
      case RepoType.MAVEN: {
        return this.mavenService.fetchKeyStores(pageIndex, pageSize);
      }
      default:
        return Promise.reject('Unsupported repository type');
    }
  }

  private getRepoSettingsService(): Promise<RepositorySettingsInfo> {
    switch (this.repoType) {
      case RepoType.MAVEN: {
        return this.mavenService.getRepoSettings();
      }
      case RepoType.NPM: {
        return this.npmService.fetchRegistrySettings();
      }
      case RepoType.PYPI: {
        return this.pypiService.fetchRepositorySettings();
      }
      case RepoType.DOCKER: {
        return this.dockerService.fetchRepositorySettings();
      }
      default:
        return Promise.reject('Unsupported repository type');
    }
  }
}
