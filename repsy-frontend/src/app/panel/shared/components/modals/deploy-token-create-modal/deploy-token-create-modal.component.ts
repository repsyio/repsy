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
import { AbstractControl, FormBuilder, FormGroup, ValidationErrors, ValidatorFn, Validators } from '@angular/forms';
import { ReactiveFormsModule } from '@angular/forms';
import moment, { Moment } from 'moment';
import { finalize } from 'rxjs/operators';

import { DeployTokenForm, ProtocolDeployTokenControllerService } from '../../../../../../generated/api';
import { idFactory } from '../../../../../shared/util/unique-id';
import { USERNAME_MESSAGES, usernameValidators } from '../../../../../shared/validators/credentials.validators';
import {
  DESCRIPTION_MAX_LENGTH,
  DESCRIPTION_MAX_MESSAGE,
  descriptionValidators,
} from '../../../../../shared/validators/description.validators';
import { TokenCreateInfo } from '../../../../pages/repository/repo-settings/deploy-token/dto/token-create-info';
import { DialogDirective } from '../../../directives/dialog.directive';
import { RadioGroupComponent, RadioOption } from '../../radio-group/radio-group.component';
import { ToastService } from '../../toast/toast.service';

@Component({
  selector: 'app-deploy-token-modal',
  imports: [DialogDirective, ReactiveFormsModule, RadioGroupComponent],
  standalone: true,
  templateUrl: './deploy-token-create-modal.component.html',
  styleUrl: './deploy-token-create-modal.component.css',
})
export class DeployTokenCreateModalComponent implements OnInit {
  /** Element ids of this instance: see `idFactory`. */
  public readonly id = idFactory('token-create');

  @Output() openChange = new EventEmitter<boolean>();
  @Output() created = new EventEmitter<TokenCreateInfo>();
  @Input() public open: boolean;
  @Input() public repoType: string;
  @Input() public repoName: string;

  public loading = false;
  public readonly usernameMessages = USERNAME_MESSAGES;
  public readonly descriptionMaxLength = DESCRIPTION_MAX_LENGTH;
  public readonly descriptionMaxMessage = DESCRIPTION_MAX_MESSAGE;

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
    private readonly protocolDeployTokenControllerService: ProtocolDeployTokenControllerService,
    private readonly fb: FormBuilder,
    private readonly toastService: ToastService,
  ) {
    this.form = this.fb.group({
      name: ['', [Validators.required, Validators.maxLength(80)]],
      // Optional; the same rule as the username of a user (the backend accepts up to 80 characters).
      username: ['', usernameValidators({ required: false })],
      description: ['', descriptionValidators()],
      readOnly: [false],
      expirationDate: [null, [this.expirationDateRangeValidator()]],
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
    const payload: DeployTokenForm | null = this.preparePayload();

    if (payload === null) {
      return;
    }

    this.loading = true;
    this.form.disable();

    this.protocolDeployTokenControllerService
      .createDeployToken(this.repoName, payload)
      .pipe(
        finalize(() => {
          this.form.enable();
          this.loading = false;
        }),
      )
      .subscribe({
        next: (r) => {
          const tokenInfo = r.data as unknown as TokenCreateInfo;
          this.closeModal();
          this.created.emit(tokenInfo);
          this.toastService.show('Deploy token created successfully.', 'success');
        },
        error: () => {},
      });
  }

  private preparePayload(): DeployTokenForm | null {
    const formValue = this.form.value;
    const payload: DeployTokenForm = {
      name: formValue.name,
      username: formValue.username?.trim() || undefined,
      description: formValue.description?.trim() || undefined,
      readOnly: formValue.readOnly,
    };

    if (formValue.expirationDate) {
      const now = moment.utc();
      const nowTime = this.getNowTime(now);

      const expiration = moment.utc(formValue.expirationDate).set(nowTime);
      const maxAllowedDate = moment.utc(this.maxDate).set(nowTime);

      if (!expiration.isValid() || expiration.isSameOrBefore(now) || expiration.isAfter(maxAllowedDate)) {
        this.toastService.show('Expiration date must be between tomorrow and one year from today.', 'error');
        return null;
      }

      payload.expirationDate = expiration.toISOString();
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

  private expirationDateRangeValidator(): ValidatorFn {
    return (control: AbstractControl): ValidationErrors | null => {
      const value = control.value;

      if (!value) {
        return null;
      }

      if (!moment.utc(value, 'YYYY-MM-DD', true).isValid()) {
        return { dateInvalid: true };
      }

      if (value < this.minDate || value > this.maxDate) {
        return { dateOutOfRange: true };
      }

      return null;
    };
  }

  protected readonly Date = Date;
}
