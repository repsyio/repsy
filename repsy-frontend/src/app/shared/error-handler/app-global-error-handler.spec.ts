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

import { Router } from '@angular/router';

import { AppGlobalErrorHandler } from './app-global-error-handler';

describe('AppGlobalErrorHandler', () => {
  const PREFIX = 'Global error handler caught an error: ';

  function handlerAt(url: string | undefined): AppGlobalErrorHandler {
    return new AppGlobalErrorHandler({ url } as Router);
  }

  beforeEach(() => {
    spyOn(console, 'error');
  });

  it('logs the message, the stack and the route of an Error', () => {
    const error = new Error('boom');
    error.stack = 'Error: boom\n    at somewhere';

    handlerAt('/repositories').handleError(error);

    expect(console.error).toHaveBeenCalledOnceWith(`${PREFIX}boom\nError: boom\n    at somewhere\n(at /repositories)`);
  });

  it('falls back to placeholders for a value that is not an Error', () => {
    handlerAt(undefined).handleError(undefined);

    expect(console.error).toHaveBeenCalledOnceWith(`${PREFIX}unknown error\nN/A\n(at N/A)`);
  });

  it('truncates a long message, stack and route', () => {
    const error = new Error('m'.repeat(20_000));
    error.stack = 's'.repeat(60_000);

    handlerAt('/' + 'u'.repeat(400)).handleError(error);

    const logged = (console.error as jasmine.Spy).calls.mostRecent().args[0] as string;
    const [message, stack, url] = logged.substring(PREFIX.length).split('\n');
    expect(message.length).toBe(9_999);
    expect(stack.length).toBe(49_999);
    expect(url).toBe(`(at /${'u'.repeat(248)})`);
  });
});
