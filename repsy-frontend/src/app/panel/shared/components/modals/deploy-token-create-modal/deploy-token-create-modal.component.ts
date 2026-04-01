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
import { FormBuilder, FormGroup, Validators } from '@angular/forms';
import { ReactiveFormsModule } from '@angular/forms';
import moment, { Moment } from 'moment';

import { DockerService } from '../../../../pages/repository/docker/service/docker.service';
import { GolangService } from '../../../../pages/repository/golang/service/golang.service';
import { MavenService } from '../../../../pages/repository/maven/service/maven.service';
import { NpmService } from '../../../../pages/repository/npm/service/npm.service';
import { PypiService } from '../../../../pages/repository/pypi/service/pypi.service';
import { TokenCreateInfo } from '../../../../pages/repository/repo-settings/deploy-token/dto/token-create-info';
import { DeployTokenForm } from '../../../../pages/repository/repo-settings/deploy-token/form/deploy-token-form';
import { RepoType } from '../../../dto/repo/repo-type';
import { ToastService } from '../../toast/toast.service';
import { RadioGroupComponent, RadioOption } from '../../radio-group/radio-group.component';

@Component({
  selector: 'app-deploy-token-modal',
  imports: [ReactiveFormsModule, RadioGroupComponent],
  standalone: true,
  templateUrl: './deploy-token-create-modal.component.html',
  styleUrl: './deploy-token-create-modal.component.css',
})
export class DeployTokenCreateModalComponent implements OnInit {
  @Output() openChange = new EventEmitter<boolean>();
  @Output() created = new EventEmitter<TokenCreateInfo>();
  @Input() public open: boolean;
  @Input() public repoType: string;

  public loading = false;

  public form: FormGroup;
  public minDate: string;
  public maxDate: string;

  public accessTypeOptions: RadioOption<boolean>[] = [
    { label: 'Read/Write', value: false },
    { label: 'Read Only', value: true },
  ];

  private todayUtc = moment.utc();
  private oneYearLaterUtc = this.todayUtc.clone().add(365, 'days').format('YYYY-MM-DD');

  constructor(
    private readonly dockerService: DockerService,
    private readonly golangService: GolangService,
    private readonly npmService: NpmService,
    private readonly mavenService: MavenService,
    private readonly pypiService: PypiService,
    private readonly fb: FormBuilder,
    private readonly toastService: ToastService,
  ) {
    this.form = this.fb.group({
      name: ['', [Validators.required, Validators.minLength(1), Validators.maxLength(80)]],
      username: ['', [Validators.minLength(3), Validators.maxLength(25), Validators.pattern(/^[a-z0-9_\-]+$/)]],
      description: ['', [Validators.maxLength(500)]],
      readOnly: [false],
      expirationDate: [],
    });
  }

  ngOnInit() {
    this.minDate = this.todayUtc.add(1, 'day').format('YYYY-MM-DD');
    this.maxDate = this.oneYearLaterUtc;
    this.form.patchValue({
      expirationDate: this.oneYearLaterUtc,
    });
  }

  closeModal(): void {
    this.form.reset({
      name: '',
      username: '',
      description: '',
      readOnly: false,
      expirationDate: this.oneYearLaterUtc,
    });
    this.openChange.emit(false);
  }

  createToken(): void {
    this.loading = true;
    this.form.disable();

    const payload: DeployTokenForm | null = this.preparePayload();

    if (payload === null) {
      return;
    }

    const onSuccess = (tokenInfo: TokenCreateInfo) => {
      this.closeModal();
      this.created.emit(tokenInfo);

      this.toastService.show('Deploy token created successfully.', 'success');
    };

    this.serviceCall(payload)
      .then((tokenInfo) => onSuccess(tokenInfo))
      .catch((err: string) => {
        this.toastService.show(err, 'error');
      })
      .finally(() => {
        this.form.enable();
        this.loading = false;
      });
  }

  private preparePayload(): DeployTokenForm | null {
    const formValue = this.form.value;
    const payload: DeployTokenForm = {
      name: formValue.name,
      username: formValue.username?.trim() || undefined,
      description: formValue.description?.trim() || undefined,
      read_only: formValue.readOnly,
    };

    if (formValue.expirationDate) {
      const now = moment.utc();
      const nowTime = this.getNowTime(now);

      const expiration = moment.utc(formValue.expirationDate).set(nowTime);
      const maxAllowedDate = moment.utc(this.maxDate).set(nowTime);

      if (!expiration.isValid() || expiration.isSameOrBefore(now) || expiration.isAfter(maxAllowedDate)) {
        this.toastService.show('Expiration date must be between tomorrow and one year from today.', 'error');
        this.form.enable();
        return null;
      }

      payload.expiration_date = expiration.toISOString();
    }

    return payload;
  }

  getNowTime(now: Moment) {
    return {
      hour: now.hour(),
      minute: now.minute(),
      second: now.second(),
      millisecond: now.millisecond(),
    };
  }

  serviceCall(form: DeployTokenForm): Promise<TokenCreateInfo> {
    switch (this.repoType) {
      case RepoType.DOCKER:
        return this.dockerService.createDeployToken(form);
      case RepoType.MAVEN:
        return this.mavenService.createDeployToken(form);
      case RepoType.NPM:
        return this.npmService.createDeployToken(form);
      case RepoType.PYPI:
        return this.pypiService.createDeployToken(form);
      case RepoType.GOLANG:
        return this.golangService.createDeployToken(form);
      default:
        return Promise.reject('Unsupported repository type: ' + this.repoType);
    }
  }

  protected readonly Date = Date;
}
