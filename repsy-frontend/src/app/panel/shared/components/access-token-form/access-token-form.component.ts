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
import { FormBuilder, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import moment from 'moment';
import { finalize } from 'rxjs/operators';

import { AccessTokenCreated, AccessTokenForm, AccessTokensApi, AccessTokenScope } from '../../../../../generated/api';
import { idFactory } from '../../../../shared/utils/unique-id';
import { MAX_ACCESS_TOKEN_NAME_LENGTH } from '../../../pages/settings/access-tokens/access-token-limits';
import { SELECTABLE_SCOPES } from '../../../pages/settings/access-tokens/access-token-scopes';
import { ToastService } from '../toast/toast.service';

/**
 * The create form of an access token, shared by the create modal of the profile page and the
 * `/cli/auth` page. It creates the token and hands it over once through `created`.
 */
@Component({
  selector: 'app-access-token-form',
  imports: [ReactiveFormsModule],
  standalone: true,
  templateUrl: './access-token-form.component.html',
})
export class AccessTokenFormComponent implements OnInit {
  /** Element ids of this instance: see `idFactory`. */
  readonly id = idFactory('access-token-form');

  @Input() initialName = '';
  @Input() initialScopes: AccessTokenScope[] = [];
  @Input() submitLabel = 'Create';
  /** When set, creating is blocked and this says why (for example the limit of tokens is reached). */
  @Input() blockedReason: string | null = null;
  @Output() created = new EventEmitter<AccessTokenCreated>();
  @Output() cancelled = new EventEmitter<void>();

  readonly scopeOptions = SELECTABLE_SCOPES;
  loading = false;
  form: FormGroup;
  selectedScopes = new Set<AccessTokenScope>();
  minDate: string;
  maxDate: string;

  constructor(
    private readonly accessTokensApi: AccessTokensApi,
    private readonly fb: FormBuilder,
    private readonly toastService: ToastService,
  ) {
    this.form = this.fb.group({
      name: ['', [Validators.required, Validators.maxLength(MAX_ACCESS_TOKEN_NAME_LENGTH)]],
      expirationDate: [null, [Validators.required]],
    });
  }

  ngOnInit(): void {
    const today = moment.utc();
    this.minDate = today.clone().add(1, 'day').format('YYYY-MM-DD');
    this.maxDate = today.clone().add(365, 'days').format('YYYY-MM-DD');
    this.selectedScopes = new Set(this.initialScopes);
    this.form.patchValue({
      name: this.initialName.trim().slice(0, MAX_ACCESS_TOKEN_NAME_LENGTH),
      expirationDate: this.maxDate,
    });
  }

  isSelected(scope: AccessTokenScope): boolean {
    return this.selectedScopes.has(scope);
  }

  toggleScope(scope: AccessTokenScope, checked: boolean): void {
    if (checked) {
      this.selectedScopes.add(scope);
    } else {
      this.selectedScopes.delete(scope);
    }
  }

  get expirationInvalid(): boolean {
    const value = this.form.get('expirationDate')?.value;
    return (
      !!value && (!moment.utc(value, 'YYYY-MM-DD', true).isValid() || value < this.minDate || value > this.maxDate)
    );
  }

  get canSubmit(): boolean {
    return (
      this.form.valid && this.selectedScopes.size > 0 && !this.expirationInvalid && !this.loading && !this.blockedReason
    );
  }

  submit(): void {
    if (!this.canSubmit) {
      return;
    }
    const payload: AccessTokenForm = {
      name: this.form.value.name.trim(),
      // The generated model types a unique list as a Set, but HttpClient serialises a Set as `{}`:
      // send the array (it is a JSON array on the wire, and the same on the way back).
      scopes: SELECTABLE_SCOPES.map((s) => s.scope).filter((s) =>
        this.selectedScopes.has(s),
      ) as unknown as Set<AccessTokenScope>,
    };
    const date = this.form.value.expirationDate;
    if (date) {
      // The date picked, at the current time of day (UTC), like the deploy token form.
      const now = moment.utc();
      payload.expirationDate = moment
        .utc(date)
        .set({ hour: now.hour(), minute: now.minute(), second: now.second(), millisecond: 0 })
        .toISOString();
    }

    this.loading = true;
    this.form.disable();
    this.accessTokensApi
      .createAccessToken(payload)
      .pipe(
        finalize(() => {
          this.form.enable();
          this.loading = false;
        }),
      )
      .subscribe({
        next: (token) => {
          this.created.emit(token);
          this.toastService.show('Access token created successfully.', 'success');
        },
        error: () => {},
      });
  }
}
