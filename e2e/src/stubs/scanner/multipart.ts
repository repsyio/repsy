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

/**
 * A minimal `multipart/form-data` reader for the one request the stub scanner accepts
 * (`POST /scan`: text fields plus at most one file part). Not a general parser: it needs the whole
 * body in memory and understands only `Content-Disposition` and `Content-Type` part headers, which
 * is all Spring's `WebClient` (the backend's client) sends.
 */

export interface MultipartFile {
  fieldName: string;
  fileName: string;
  size: number;
}

export interface MultipartForm {
  fields: Record<string, string>;
  files: MultipartFile[];
}

/** The `boundary` parameter of a `Content-Type` header, or undefined when it is not multipart. */
export function boundaryOf(contentType: string | undefined): string | undefined {
  if (!contentType || !/^multipart\/form-data/i.test(contentType)) {
    return undefined;
  }
  const match = /boundary=(?:"([^"]+)"|([^;\s]+))/i.exec(contentType);
  return match ? (match[1] ?? match[2]) : undefined;
}

const CRLF = Buffer.from('\r\n');
const HEADER_END = Buffer.from('\r\n\r\n');

/** Splits `body` into its text fields and file parts (of which only the size is kept). */
export function parseMultipart(body: Buffer, boundary: string): MultipartForm {
  const delimiter = Buffer.from(`--${boundary}`);
  const form: MultipartForm = { fields: {}, files: [] };

  let position = body.indexOf(delimiter);
  while (position !== -1) {
    const partStart = position + delimiter.length;
    // `--` right after a delimiter closes the body.
    if (body.subarray(partStart, partStart + 2).toString('latin1') === '--') {
      break;
    }
    const next = body.indexOf(delimiter, partStart);
    if (next === -1) {
      break;
    }
    // A part is `CRLF headers CRLF CRLF content CRLF`; the last CRLF belongs to the delimiter.
    const part = body.subarray(partStart + CRLF.length, next - CRLF.length);
    readPart(part, form);
    position = next;
  }
  return form;
}

function readPart(part: Buffer, form: MultipartForm): void {
  const headerEnd = part.indexOf(HEADER_END);
  if (headerEnd === -1) {
    return;
  }
  const headers = part.subarray(0, headerEnd).toString('utf8');
  const content = part.subarray(headerEnd + HEADER_END.length);
  const disposition = /^content-disposition:(.*)$/im.exec(headers)?.[1] ?? '';
  const name = /\bname="([^"]*)"/i.exec(disposition)?.[1];
  if (name === undefined) {
    return;
  }
  const fileName = /\bfilename="([^"]*)"/i.exec(disposition)?.[1];
  if (fileName === undefined) {
    form.fields[name] = content.toString('utf8');
  } else {
    form.files.push({ fieldName: name, fileName, size: content.length });
  }
}
