import {HttpClient, HttpErrorResponse} from '@angular/common/http';
import {Injectable}                    from '@angular/core';
import {BehaviorSubject, Observable}   from 'rxjs';

import {environment}                 from '../../../../../../environments/environment';
import {ErrorHandlerService}         from '../../../../../shared/error-handler/error-handler.service';
import {RepoForm}                    from '../../../../shared/dto/repo/repo-form';
import {RepoPermissionInfo}          from '../../../../shared/dto/repo/repo-permission-info';
import {RestResponse}                from '../../../../shared/dto/rest-response';

@Injectable({
  providedIn: 'root',
})
export class CargoService {
  public readonly repoChanges: Observable<RepoPermissionInfo>;

  private activeRepo: RepoPermissionInfo;

  private readonly apiBaseUrl: string = environment.apiBaseUrl;
  private readonly repoSubject        = new BehaviorSubject<RepoPermissionInfo>(null);

  constructor(
    private readonly http: HttpClient,
    private readonly errorHandlerService: ErrorHandlerService,
  ) {
    this.repoChanges = this.repoSubject.asObservable();
  }

  public async createRepository(repoForm: RepoForm): Promise<void> {
    return new Promise<void>((resolve, reject) => {
      const url = `${this.apiBaseUrl}/api/cargo/repo`;

      this.http
        .post<RestResponse<void>>(url, repoForm)
        .toPromise()
        .then(() => resolve())
        .catch((res: HttpErrorResponse) => reject(this.errorHandlerService.handle(res)));
    });
  }
}
