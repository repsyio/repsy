/*
 * Copyright 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.repsy.os.server.protocols.shared;

/**
 * The transcripts {@link OciWireCharacterizationIT} pins, recorded on the Docker and Helm OCI
 * handlers before they moved onto the shared OCI module (RPS-2058). Placeholders: {@code {repo}}
 * and {@code {other}} are the two repos of the run, {@code {uuid-N}} and {@code {digest-N}} number
 * the upload ids and digests in their order of appearance.
 */
final class OciWireTranscripts {

  static final String DOCKER =
      """
      ## ping GET
      > GET /v2/
      < 200
      < Docker-Distribution-API-Version: registry/2.0
      ## ping HEAD
      > HEAD /v2/
      < 200
      < Docker-Distribution-API-Version: registry/2.0
      ## upload start without credentials
      > POST /v2/{repo}/app/blobs/uploads/
      < 401
      < Content-Type: application/json
      < WWW-Authenticate: Bearer realm="http://localhost/v2/token",service="repsy",scope="repository:{repo}/app:pull,push"
      < body: {"errors":[{"code":"UNAUTHORIZED","message":"The credentials are missing, invalid or expired, or they do not allow this action.","detail":"unAuthorized"}]}
      ## blob check of a missing blob
      > HEAD /v2/{repo}/app/blobs/{digest-1}
      < 404
      ## blob pull of a missing blob
      > GET /v2/{repo}/app/blobs/{digest-1}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"BLOB_UNKNOWN","message":"Layer not found.","detail":"layerNotFound"}]}
      ## blob pull of a malformed digest
      > GET /v2/{repo}/app/blobs/sha256:abc
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"NAME_UNKNOWN","message":"unknownPath","detail":"unknownPath"}]}
      ## upload start with an unsupported digest-algorithm
      > POST /v2/{repo}/app/blobs/uploads/
      < 400
      < Content-Type: application/json
      < body: {"errors":[{"code":"DIGEST_INVALID","message":"The digest-algorithm of the upload is not supported; sha256 and sha512 are.","detail":"dockerDigestAlgorithmUnsupported"}]}
      ## cross-repo mount of a missing blob
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-1}
      < Docker-Upload-UUID: {uuid-1}
      ## status of an unknown upload
      > GET /v2/{repo}/app/blobs/uploads/{uuid-2}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"BLOB_UPLOAD_UNKNOWN","message":"Resource not found.","detail":"resourceNotFound"}]}
      ## finalize of an unknown upload
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-2}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"BLOB_UPLOAD_UNKNOWN","message":"Resource not found.","detail":"resourceNotFound"}]}
      ## chunked upload start
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-3}
      < Docker-Upload-UUID: {uuid-3}
      ## chunk without Content-Range
      > PATCH /v2/{repo}/app/blobs/uploads/{uuid-3}
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-3}
      < Range: 0-5
      < Docker-Upload-UUID: {uuid-3}
      ## chunk whose Content-Range starts at the upload size
      > PATCH /v2/{repo}/app/blobs/uploads/{uuid-3}
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-3}
      < Range: 0-10
      < Docker-Upload-UUID: {uuid-3}
      ## chunk whose Content-Range does not start at the upload size
      > PATCH /v2/{repo}/app/blobs/uploads/{uuid-3}
      < 416
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-3}
      < Range: 0-10
      < Docker-Upload-UUID: {uuid-3}
      ## chunk with a Content-Range the parser cannot read is appended
      > PATCH /v2/{repo}/app/blobs/uploads/{uuid-3}
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-3}
      < Range: 0-11
      < Docker-Upload-UUID: {uuid-3}
      ## upload status GET
      > GET /v2/{repo}/app/blobs/uploads/{uuid-3}
      < 204
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-3}
      < Range: 0-11
      < Docker-Upload-UUID: {uuid-3}
      ## upload status HEAD
      > HEAD /v2/{repo}/app/blobs/uploads/{uuid-3}
      < 204
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-3}
      < Range: 0-11
      < Docker-Upload-UUID: {uuid-3}
      ## finalize without digest
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-3}
      < 400
      < Content-Type: application/json
      < body: {"errors":[{"code":"UNSUPPORTED","message":"The upload needs a digest query parameter.","detail":"digestMissing"}]}
      ## finalize
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-3}
      < 201
      < Location: http://localhost/v2/{repo}/app/blobs/{digest-2}
      < Docker-Upload-UUID: {uuid-3}
      < Docker-Content-Digest: {digest-2}
      ## upload start for a digest mismatch
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-4}
      < Docker-Upload-UUID: {uuid-4}
      ## finalize whose digest does not match the bytes
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-4}
      < 400
      < Content-Type: application/json
      < body: {"errors":[{"code":"DIGEST_INVALID","message":"digestMismatch","detail":"digestMismatch"}]}
      ## upload start for a sha512 digest
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-5}
      < Docker-Upload-UUID: {uuid-5}
      ## monolithic finalize with a sha512 digest
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-5}
      < 201
      < Location: http://localhost/v2/{repo}/app/blobs/{digest-3}
      < Docker-Upload-UUID: {uuid-5}
      < Docker-Content-Digest: {digest-3}
      ## upload start for the config
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-6}
      < Docker-Upload-UUID: {uuid-6}
      ## monolithic finalize of the config
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-6}
      < 201
      < Location: http://localhost/v2/{repo}/app/blobs/{digest-4}
      < Docker-Upload-UUID: {uuid-6}
      < Docker-Content-Digest: {digest-4}
      ## upload start for the layer
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-7}
      < Docker-Upload-UUID: {uuid-7}
      ## monolithic finalize of the layer
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-7}
      < 201
      < Location: http://localhost/v2/{repo}/app/blobs/{digest-5}
      < Docker-Upload-UUID: {uuid-7}
      < Docker-Content-Digest: {digest-5}
      ## cross-repo mount of a blob the source repo lacks
      > POST /v2/{other}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{other}/app/blobs/uploads/{uuid-8}
      < Docker-Upload-UUID: {uuid-8}
      ## blob check
      > HEAD /v2/{repo}/app/blobs/{digest-2}
      < 200
      < Docker-Content-Digest: {digest-2}
      < Content-Type: application/vnd.docker.image.rootfs.diff.tar.gzip
      < Content-Length: 12
      ## blob pull
      > GET /v2/{repo}/app/blobs/{digest-2}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"BLOB_UNKNOWN","message":"Layer not found.","detail":"layerNotFound"}]}
      ## blob check of the layer
      > HEAD /v2/{repo}/app/blobs/{digest-5}
      < 200
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.docker.image.rootfs.diff.tar.gzip
      < Content-Length: 12
      ## manifest check of a missing tag
      > HEAD /v2/{repo}/app/manifests/1.0.0
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"NAME_UNKNOWN","message":"Image not found.","detail":"imageNotFound"}]}
      ## manifest pull of a missing tag
      > GET /v2/{repo}/app/manifests/1.0.0
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"NAME_UNKNOWN","message":"Image not found.","detail":"imageNotFound"}]}
      ## manifest push by tag
      > PUT /v2/{repo}/app/manifests/1.0.0
      < 201
      < Location: http://localhost/v2/{repo}/app/manifests/{digest-6}
      < Docker-Content-Digest: {digest-6}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      ## manifest push by digest
      > PUT /v2/{repo}/app/manifests/{digest-6}
      < 201
      < Location: http://localhost/v2/{repo}/app/manifests/{digest-6}
      < Docker-Content-Digest: {digest-6}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      ## manifest push by a second tag
      > PUT /v2/{repo}/app/manifests/latest
      < 201
      < Location: http://localhost/v2/{repo}/app/manifests/{digest-6}
      < Docker-Content-Digest: {digest-6}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      ## blob pull of the layer
      > GET /v2/{repo}/app/blobs/{digest-5}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"BLOB_UNKNOWN","message":"Layer not found.","detail":"layerNotFound"}]}
      ## manifest check by tag
      > HEAD /v2/{repo}/app/manifests/1.0.0
      < 200
      < Docker-Content-Digest: {digest-6}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 399
      ## manifest check by digest
      > HEAD /v2/{repo}/app/manifests/{digest-6}
      < 200
      < Docker-Content-Digest: {digest-6}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 399
      ## manifest pull by tag
      > GET /v2/{repo}/app/manifests/1.0.0
      < 200
      < Docker-Content-Digest: {digest-6}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 399
      < body: {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"{digest-4}","size":37},"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"{digest-5}","size":12}]}
      ## manifest pull by digest
      > GET /v2/{repo}/app/manifests/{digest-6}
      < 200
      < Docker-Content-Digest: {digest-6}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 399
      < body: {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"{digest-4}","size":37},"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"{digest-5}","size":12}]}
      ## manifest pull with an Accept header naming no manifest type
      > GET /v2/{repo}/app/manifests/1.0.0
      < 406
      < Content-Type: application/json
      < body: {"errors":[{"code":"UNSUPPORTED","message":"None of the requested media types can be produced.","detail":"notAcceptable"}]}
      ## manifest pull accepting the OCI manifest type
      > GET /v2/{repo}/app/manifests/1.0.0
      < 200
      < Docker-Content-Digest: {digest-6}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 399
      < body: {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.oci.image.config.v1+json","digest":"{digest-4}","size":37},"layers":[{"mediaType":"application/vnd.oci.image.layer.v1.tar+gzip","digest":"{digest-5}","size":12}]}
      ## manifest check of a missing digest
      > HEAD /v2/{repo}/app/manifests/{digest-1}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"MANIFEST_UNKNOWN","message":"Manifest not found.","detail":"manifestNotFound"}]}
      ## tags list
      > GET /v2/{repo}/app/tags/list
      < 200
      < Content-Type: application/json
      < body: {"name":"{repo}/app","tags":["1.0.0","latest"]}
      ## tags list with n=1
      > GET /v2/{repo}/app/tags/list
      < 200
      < Content-Type: application/json
      < Link: </v2/{repo}/app/tags/list?n=1&last=1.0.0>; rel="next"
      < body: {"name":"{repo}/app","tags":["1.0.0"]}
      ## tags list with n=-1
      > GET /v2/{repo}/app/tags/list
      < 400
      < Content-Type: application/json
      < body: {"errors":[{"code":"PAGINATION_NUMBER_INVALID","message":"The n of a tag listing must be a non-negative integer.","detail":"paginationNumberInvalid"}]}
      ## tags list of an unknown name
      > GET /v2/{repo}/nothing/tags/list
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"NAME_UNKNOWN","message":"Image not found.","detail":"imageNotFound"}]}
      ## tags list without credentials
      > GET /v2/{repo}/app/tags/list
      < 401
      < Content-Type: application/json
      < WWW-Authenticate: Bearer realm="http://localhost/v2/token",service="repsy",scope="repository:{repo}/app:pull"
      < body: {"errors":[{"code":"UNAUTHORIZED","message":"Authentication is required to access this resource.","detail":"unauthorizedRequest"}]}
      ## unknown route under a name
      > GET /v2/{repo}/app/unknown
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"NAME_UNKNOWN","message":"unknownPath","detail":"unknownPath"}]}
      ## manifest delete by digest
      > DELETE /v2/{repo}/app/manifests/{digest-6}
      < 202
      ## manifest pull after the delete
      > GET /v2/{repo}/app/manifests/{digest-6}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"NAME_UNKNOWN","message":"Image not found.","detail":"imageNotFound"}]}
      """;

  static final String HELM =
      """
      ## ping GET
      > GET /v2/
      < 200
      < Docker-Distribution-API-Version: registry/2.0
      ## ping HEAD
      > HEAD /v2/
      < 200
      < Docker-Distribution-API-Version: registry/2.0
      ## upload start without credentials
      > POST /v2/{repo}/app/blobs/uploads/
      < 401
      < Content-Type: application/json
      < WWW-Authenticate: Basic realm="Repsy"
      < body: {"errors":[{"code":"UNAUTHORIZED","message":"Authentication is required to access this resource.","detail":"unauthorizedRequest"}]}
      ## blob check of a missing blob
      > HEAD /v2/{repo}/app/blobs/{digest-1}
      < 404
      ## blob pull of a missing blob
      > GET /v2/{repo}/app/blobs/{digest-1}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"BLOB_UNKNOWN","message":"blobNotFound","detail":"blobNotFound"}]}
      ## blob pull of a malformed digest
      > GET /v2/{repo}/app/blobs/sha256:abc
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"NAME_UNKNOWN","message":"unknownPath","detail":"unknownPath"}]}
      ## upload start with an unsupported digest-algorithm
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-1}
      < Docker-Upload-UUID: {uuid-1}
      ## cross-repo mount of a missing blob
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-2}
      < Docker-Upload-UUID: {uuid-2}
      ## status of an unknown upload
      > GET /v2/{repo}/app/blobs/uploads/{uuid-3}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"BLOB_UNKNOWN","message":"blobNotFound","detail":"blobNotFound"}]}
      ## finalize of an unknown upload
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-3}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"BLOB_UNKNOWN","message":"blobNotFound","detail":"blobNotFound"}]}
      ## chunked upload start
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-4}
      < Docker-Upload-UUID: {uuid-4}
      ## chunk without Content-Range
      > PATCH /v2/{repo}/app/blobs/uploads/{uuid-4}
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-4}
      < Range: 0-5
      < Docker-Upload-UUID: {uuid-4}
      ## chunk whose Content-Range starts at the upload size
      > PATCH /v2/{repo}/app/blobs/uploads/{uuid-4}
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-4}
      < Range: 0-10
      < Docker-Upload-UUID: {uuid-4}
      ## chunk whose Content-Range does not start at the upload size
      > PATCH /v2/{repo}/app/blobs/uploads/{uuid-4}
      < 416
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-4}
      < Range: 0-10
      < Docker-Upload-UUID: {uuid-4}
      ## chunk with a Content-Range the parser cannot read is appended
      > PATCH /v2/{repo}/app/blobs/uploads/{uuid-4}
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-4}
      < Range: 0-11
      < Docker-Upload-UUID: {uuid-4}
      ## upload status GET
      > GET /v2/{repo}/app/blobs/uploads/{uuid-4}
      < 204
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-4}
      < Range: 0-11
      < Docker-Upload-UUID: {uuid-4}
      ## upload status HEAD
      > HEAD /v2/{repo}/app/blobs/uploads/{uuid-4}
      < 204
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-4}
      < Range: 0-11
      < Docker-Upload-UUID: {uuid-4}
      ## finalize without digest
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-4}
      < 400
      < Content-Type: application/json
      < body: {"errors":[{"code":"UNSUPPORTED","message":"The upload needs a digest query parameter.","detail":"digestMissing"}]}
      ## finalize
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-4}
      < 201
      < Location: http://localhost/v2/{repo}/app/blobs/{digest-2}
      < Docker-Upload-UUID: {uuid-4}
      < Docker-Content-Digest: {digest-2}
      ## upload start for a digest mismatch
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-5}
      < Docker-Upload-UUID: {uuid-5}
      ## finalize whose digest does not match the bytes
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-5}
      < 400
      < Content-Type: application/json
      < body: {"errors":[{"code":"DIGEST_INVALID","message":"digestMismatch","detail":"digestMismatch"}]}
      ## upload start for a sha512 digest
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-6}
      < Docker-Upload-UUID: {uuid-6}
      ## monolithic finalize with a sha512 digest
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-6}
      < 400
      < Content-Type: application/json
      < body: {"errors":[{"code":"DIGEST_INVALID","message":"Only sha256 digests are supported for blobs.","detail":"blobDigestUnsupported"}]}
      ## upload start for the config
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-7}
      < Docker-Upload-UUID: {uuid-7}
      ## monolithic finalize of the config
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-7}
      < 201
      < Location: http://localhost/v2/{repo}/app/blobs/{digest-3}
      < Docker-Upload-UUID: {uuid-7}
      < Docker-Content-Digest: {digest-3}
      ## upload start for the layer
      > POST /v2/{repo}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{repo}/app/blobs/uploads/{uuid-8}
      < Docker-Upload-UUID: {uuid-8}
      ## monolithic finalize of the layer
      > PUT /v2/{repo}/app/blobs/uploads/{uuid-8}
      < 201
      < Location: http://localhost/v2/{repo}/app/blobs/{digest-4}
      < Docker-Upload-UUID: {uuid-8}
      < Docker-Content-Digest: {digest-4}
      ## cross-repo mount of a blob the source repo lacks
      > POST /v2/{other}/app/blobs/uploads/
      < 202
      < Location: http://localhost/v2/{other}/app/blobs/uploads/{uuid-9}
      < Docker-Upload-UUID: {uuid-9}
      ## blob check
      > HEAD /v2/{repo}/app/blobs/{digest-2}
      < 200
      < Docker-Content-Digest: {digest-2}
      < Content-Type: application/octet-stream
      < Content-Length: 12
      ## blob pull
      > GET /v2/{repo}/app/blobs/{digest-2}
      < 200
      < Docker-Content-Digest: {digest-2}
      < Content-Type: application/octet-stream
      < Content-Length: 12
      < body: hello world!
      ## blob check of the layer
      > HEAD /v2/{repo}/app/blobs/{digest-4}
      < 200
      < Docker-Content-Digest: {digest-4}
      < Content-Type: application/octet-stream
      < Content-Length: 136
      ## manifest check of a missing tag
      > HEAD /v2/{repo}/app/manifests/1.0.0
      < 404
      ## manifest pull of a missing tag
      > GET /v2/{repo}/app/manifests/1.0.0
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"MANIFEST_UNKNOWN","message":"Manifest not found.","detail":"manifestNotFound"}]}
      ## manifest push by tag
      > PUT /v2/{repo}/app/manifests/1.0.0
      < 201
      < Location: http://localhost/v2/{repo}/app/manifests/{digest-5}
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      ## manifest push by digest
      > PUT /v2/{repo}/app/manifests/{digest-5}
      < 201
      < Location: http://localhost/v2/{repo}/app/manifests/{digest-5}
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      ## manifest push by a second tag
      > PUT /v2/{repo}/app/manifests/latest
      < 201
      < Location: http://localhost/v2/{repo}/app/manifests/{digest-5}
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      ## blob pull of the layer
      > GET /v2/{repo}/app/blobs/{digest-4}
      < 200
      < Docker-Content-Digest: {digest-4}
      < Content-Type: application/octet-stream
      < Content-Length: 136
      < body: <136 bytes, {digest-4}>
      ## manifest check by tag
      > HEAD /v2/{repo}/app/manifests/1.0.0
      < 200
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 407
      ## manifest check by digest
      > HEAD /v2/{repo}/app/manifests/{digest-5}
      < 200
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 407
      ## manifest pull by tag
      > GET /v2/{repo}/app/manifests/1.0.0
      < 200
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 407
      < body: {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.cncf.helm.config.v1+json","digest":"{digest-3}","size":2},"layers":[{"mediaType":"application/vnd.cncf.helm.chart.content.v1.tar+gzip","digest":"{digest-4}","size":136}]}
      ## manifest pull by digest
      > GET /v2/{repo}/app/manifests/{digest-5}
      < 200
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 407
      < body: {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.cncf.helm.config.v1+json","digest":"{digest-3}","size":2},"layers":[{"mediaType":"application/vnd.cncf.helm.chart.content.v1.tar+gzip","digest":"{digest-4}","size":136}]}
      ## manifest pull with an Accept header naming no manifest type
      > GET /v2/{repo}/app/manifests/1.0.0
      < 200
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 407
      < body: {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.cncf.helm.config.v1+json","digest":"{digest-3}","size":2},"layers":[{"mediaType":"application/vnd.cncf.helm.chart.content.v1.tar+gzip","digest":"{digest-4}","size":136}]}
      ## manifest pull accepting the OCI manifest type
      > GET /v2/{repo}/app/manifests/1.0.0
      < 200
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 407
      < body: {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.cncf.helm.config.v1+json","digest":"{digest-3}","size":2},"layers":[{"mediaType":"application/vnd.cncf.helm.chart.content.v1.tar+gzip","digest":"{digest-4}","size":136}]}
      ## manifest check of a missing digest
      > HEAD /v2/{repo}/app/manifests/{digest-1}
      < 404
      ## tags list
      > GET /v2/{repo}/app/tags/list
      < 200
      < Content-Type: application/json
      < body: {"name":"{repo}/app","tags":["1.0.0","latest"]}
      ## tags list with n=1
      > GET /v2/{repo}/app/tags/list
      < 200
      < Content-Type: application/json
      < body: {"name":"{repo}/app","tags":["1.0.0","latest"]}
      ## tags list with n=-1
      > GET /v2/{repo}/app/tags/list
      < 200
      < Content-Type: application/json
      < body: {"name":"{repo}/app","tags":["1.0.0","latest"]}
      ## tags list of an unknown name
      > GET /v2/{repo}/nothing/tags/list
      < 200
      < Content-Type: application/json
      < body: {"name":"{repo}/nothing","tags":[]}
      ## tags list without credentials
      > GET /v2/{repo}/app/tags/list
      < 200
      < Content-Type: application/json
      < body: {"name":"{repo}/app","tags":["1.0.0","latest"]}
      ## unknown route under a name
      > GET /v2/{repo}/app/unknown
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"NAME_UNKNOWN","message":"unknownPath","detail":"unknownPath"}]}
      ## manifest delete by digest
      > DELETE /v2/{repo}/app/manifests/{digest-5}
      < 404
      < Content-Type: application/json
      < body: {"errors":[{"code":"NAME_UNKNOWN","message":"unknownPath","detail":"unknownPath"}]}
      ## manifest pull after the delete
      > GET /v2/{repo}/app/manifests/{digest-5}
      < 200
      < Docker-Content-Digest: {digest-5}
      < Content-Type: application/vnd.oci.image.manifest.v1+json
      < Content-Length: 407
      < body: {"schemaVersion":2,"mediaType":"application/vnd.oci.image.manifest.v1+json","config":{"mediaType":"application/vnd.cncf.helm.config.v1+json","digest":"{digest-3}","size":2},"layers":[{"mediaType":"application/vnd.cncf.helm.chart.content.v1.tar+gzip","digest":"{digest-4}","size":136}]}
      """;

  private OciWireTranscripts() {}
}
