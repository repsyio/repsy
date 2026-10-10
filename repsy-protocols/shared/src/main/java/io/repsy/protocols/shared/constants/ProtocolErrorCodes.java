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
package io.repsy.protocols.shared.constants;

import lombok.NoArgsConstructor;
import org.jspecify.annotations.NullMarked;

/**
 * The error codes (the {@code msgId} of a failure, the {@code code} of a problem document) that the
 * protocol libraries and the shared backend code emit. The strings are the wire contract: the panel
 * frontend and the package manager clients switch on them, so a value never changes with a rename
 * of its constant.
 *
 * <p>Codes that only the panel emits live in the backend {@code ErrorConstants}. Every constant is
 * named after its value in {@code UPPER_SNAKE_CASE}; the pin test {@code ProtocolErrorCodesTest}
 * fails on a changed, added or removed value.
 */
@NullMarked
@NoArgsConstructor
public final class ProtocolErrorCodes {

  public static final String ACCESS_DENIED = "accessDenied";
  public static final String ACCESS_NOT_ALLOWED = "accessNotAllowed";
  public static final String ALLOWED_KEYSERVER_NOT_FOUND = "allowedKeyserverNotFound";
  public static final String ARCHIVE_FILE_NAME_INVALID = "archiveFileNameInvalid";
  public static final String ARCHIVE_FILE_NAME_NULL = "archiveFileNameNull";
  public static final String ARCHIVE_VERSION_MISMATCH = "archiveVersionMismatch";
  public static final String ARTIFACT_ID_TOO_LONG = "artifactIdTooLong";
  public static final String ARTIFACT_NOT_FOUND = "artifactNotFound";
  public static final String ARTIFACT_OVERRIDE_IS_PROHIBITED = "artifactOverrideIsProhibited";
  public static final String ARTIFACT_SIGNATURE_NOT_VERIFIED = "artifactSignatureNotVerified";
  public static final String ARTIFACT_SIGNING_KEY_NOT_FOUND = "artifactSigningKeyNotFound";
  public static final String ARTIFACT_SIGNING_KEY_NOT_REGISTERED =
      "artifactSigningKeyNotRegistered";
  public static final String ARTIFACT_VERSION_NEVER_SCANNED = "artifactVersionNeverScanned";
  public static final String ARTIFACT_VERSION_NOT_FOUND = "artifactVersionNotFound";
  public static final String BAD_PACKAGE_METADATA = "badPackageMetadata";
  public static final String BAD_REQUEST = "badRequest";
  public static final String BAD_VERSION_STRING = "badVersionString";
  public static final String BLOB_DIGEST_UNSUPPORTED = "blobDigestUnsupported";
  public static final String BLOB_NOT_FOUND = "blobNotFound";
  public static final String CAN_NOT_REMOVE_TAG_LATEST = "canNotRemoveTagLatest";
  public static final String CHART_ALREADY_EXISTS = "chartAlreadyExists";
  public static final String CHART_API_VERSION_INVALID = "chartApiVersionInvalid";
  public static final String CHART_APP_VERSION_INVALID = "chartAppVersionInvalid";
  public static final String CHART_APP_VERSION_TOO_LONG = "chartAppVersionTooLong";
  public static final String CHART_DEPENDENCIES_INVALID = "chartDependenciesInvalid";
  public static final String CHART_DESCRIPTION_INVALID = "chartDescriptionInvalid";
  public static final String CHART_NAME_INVALID = "chartNameInvalid";
  public static final String CHART_NAME_MISMATCH = "chartNameMismatch";
  public static final String CHART_NAME_MISSING = "chartNameMissing";
  public static final String CHART_NAME_TOO_LONG = "chartNameTooLong";
  public static final String CHART_NOT_FOUND = "chartNotFound";
  public static final String CHART_TYPE_INVALID = "chartTypeInvalid";
  public static final String CHART_VERSION_INVALID = "chartVersionInvalid";
  public static final String CHART_VERSION_MISSING = "chartVersionMissing";
  public static final String CHART_VERSION_TOO_LONG = "chartVersionTooLong";
  public static final String CHART_YAML_INVALID = "chartYamlInvalid";
  public static final String CHART_YAML_NOT_FOUND = "chartYamlNotFound";
  public static final String CHART_YAML_TOO_LARGE = "chartYamlTooLarge";
  public static final String CLEANUP_POLICY_DISABLED = "cleanupPolicyDisabled";
  public static final String CLEANUP_POLICY_NOT_FOUND = "cleanupPolicyNotFound";
  public static final String CONCURRENT_MODIFICATION = "concurrentModification";
  public static final String CRATE_NAME_SPELLING_MISMATCH = "crateNameSpellingMismatch";
  public static final String CRATE_NOT_FOUND = "crateNotFound";
  public static final String CRATE_VERSION_ALREADY_EXISTS = "crateVersionAlreadyExists";
  public static final String CRATE_VERSION_NOT_FOUND = "crateVersionNotFound";
  public static final String DEPLOY_TOKEN_EXPIRATION_IN_PAST = "deployTokenExpirationInPast";
  public static final String DEPLOY_TOKEN_EXPIRATION_TOO_LATE = "deployTokenExpirationTooLate";
  public static final String DEPLOY_TOKEN_EXPIRED = "deployTokenExpired";
  public static final String DEPLOY_TOKEN_NOT_REVOCABLE = "deployTokenNotRevocable";
  public static final String DEPRECATION_MESSAGE_TOO_LONG = "deprecationMessageTooLong";
  public static final String DIGEST_MISMATCH = "digestMismatch";
  public static final String DIGEST_MISSING = "digestMissing";
  public static final String DIST_TAG_NAME_TOO_LONG = "distTagNameTooLong";
  public static final String DOCKER_DIGEST_ALGORITHM_UNSUPPORTED =
      "dockerDigestAlgorithmUnsupported";
  public static final String DOCKER_DIGEST_INVALID = "dockerDigestInvalid";
  public static final String DOCKER_IMAGE_NAME_INVALID = "dockerImageNameInvalid";
  public static final String DOCKER_MEDIA_TYPE_TOO_LONG = "dockerMediaTypeTooLong";
  public static final String DOCKER_PATH_INVALID = "dockerPathInvalid";
  public static final String DOCKER_PLATFORM_TOO_LONG = "dockerPlatformTooLong";
  public static final String DOCKER_REFERENCE_INVALID = "dockerReferenceInvalid";
  public static final String ERROR_OCCURRED = "errorOccurred";
  public static final String FILE_ALREADY_EXISTS = "fileAlreadyExists";
  public static final String GEM_DEPENDENCY_NAME_TOO_LONG = "gemDependencyNameTooLong";
  public static final String GEM_DEPENDENCY_REQUIREMENTS_TOO_LONG =
      "gemDependencyRequirementsTooLong";
  public static final String GEM_METADATA_TOO_LARGE = "gemMetadataTooLarge";
  public static final String GEM_NAME_MISSING = "gemNameMissing";
  public static final String GEM_NAME_TOO_LONG = "gemNameTooLong";
  public static final String GEM_NOT_FOUND = "gemNotFound";
  public static final String GEM_PLATFORM_TOO_LONG = "gemPlatformTooLong";
  public static final String GEM_REQUIRED_RUBY_VERSION_TOO_LONG = "gemRequiredRubyVersionTooLong";
  public static final String GEM_VERSION_ALREADY_EXISTS = "gemVersionAlreadyExists";
  public static final String GEM_VERSION_ALREADY_YANKED = "gemVersionAlreadyYanked";
  public static final String GEM_VERSION_MISSING = "gemVersionMissing";
  public static final String GEM_VERSION_NOT_FOUND = "gemVersionNotFound";
  public static final String GEM_VERSION_TOO_LONG = "gemVersionTooLong";
  public static final String GO_MODULE_BUSY = "goModuleBusy";
  public static final String GO_MODULE_VERSION_ALREADY_EXISTS = "goModuleVersionAlreadyExists";
  public static final String GO_MODULE_ZIP_EMPTY = "goModuleZipEmpty";
  public static final String GO_MOD_FILE_EMPTY = "goModFileEmpty";
  public static final String GO_MOD_INVALID_MODULE_PATH = "goModInvalidModulePath";
  public static final String GO_MOD_MISSING_MODULE_DIRECTIVE = "goModMissingModuleDirective";
  public static final String GO_MOD_MODULE_PATH_MISMATCH = "goModModulePathMismatch";
  public static final String GO_MOD_NOT_FOUND_IN_ZIP = "goModNotFoundInZip";
  public static final String GO_MOD_TOO_LARGE = "goModTooLarge";
  public static final String GROUP_ID_TOO_LONG = "groupIdTooLong";
  public static final String GROUP_NOT_FOUND = "groupNotFound";
  public static final String HELM_CHART_EMPTY = "helmChartEmpty";
  public static final String IMAGE_NOT_FOUND = "imageNotFound";
  public static final String INVALID_ARTIFACT_PATH = "invalidArtifactPath";
  public static final String INVALID_GEM_FILE = "invalidGemFile";
  public static final String INVALID_MODULE_PATH = "invalidModulePath";
  public static final String INVALID_MODULE_VERSION = "invalidModuleVersion";
  public static final String INVALID_PACKAGE_VERSION = "invalidPackageVersion";
  public static final String INVALID_REGEX = "invalidRegex";
  public static final String INVALID_REQUEST = "invalidRequest";
  public static final String INVALID_SEARCH_PARAMETER = "invalidSearchParameter";
  public static final String INVALID_STORAGE_PATH = "invalidStoragePath";
  public static final String ITEM_ALREADY_EXISTS = "itemAlreadyExists";
  public static final String ITEM_NOT_FOUND = "itemNotFound";
  public static final String KEY_STORE_ALREADY_EXISTS = "keyStoreAlreadyExists";
  public static final String KEY_STORE_NOT_FOUND = "keyStoreNotFound";
  public static final String LAYER_NOT_FOUND = "layerNotFound";
  public static final String LOGIN_REQUIRED = "loginRequired";
  public static final String LOGIN_TOKEN_NOT_FOUND = "loginTokenNotFound";
  public static final String LOGIN_TOKEN_NOT_YOURS = "loginTokenNotYours";
  public static final String MALFORMED_METADATA_FILE = "malformedMetadataFile";
  public static final String MALFORMED_POM_FILE = "malformedPomFile";
  public static final String MANIFEST_CHART_LAYER_MISSING = "manifestChartLayerMissing";
  public static final String MANIFEST_CONFIG_INVALID = "manifestConfigInvalid";
  public static final String MANIFEST_CONFIG_MISSING = "manifestConfigMissing";
  public static final String MANIFEST_CONTENT_TYPE_MISSING = "manifestContentTypeMissing";
  public static final String MANIFEST_INVALID = "manifestInvalid";
  public static final String MANIFEST_INVALID_JSON = "manifestInvalidJson";
  public static final String MANIFEST_LAYERS_INVALID = "manifestLayersInvalid";
  public static final String MANIFEST_LAYERS_MISSING = "manifestLayersMissing";
  public static final String MANIFEST_LAYER_INVALID = "manifestLayerInvalid";
  public static final String MANIFEST_LIST_MANIFESTS_INVALID = "manifestListManifestsInvalid";
  public static final String MANIFEST_MEDIA_TYPE_TOO_LONG = "manifestMediaTypeTooLong";
  public static final String MANIFEST_MEDIA_TYPE_UNSUPPORTED = "manifestMediaTypeUnsupported";
  public static final String MANIFEST_NAME_TOO_LONG = "manifestNameTooLong";
  public static final String MANIFEST_NOT_FOUND = "manifestNotFound";
  public static final String MANIFEST_REFERENCE_TOO_LONG = "manifestReferenceTooLong";
  public static final String MANIFEST_SCHEMA_VERSION_INVALID = "manifestSchemaVersionInvalid";
  public static final String MAVEN_FILE_NAME_TOO_LONG = "mavenFileNameTooLong";
  public static final String MAVEN_METADATA_TOO_LARGE = "mavenMetadataTooLarge";
  public static final String MAVEN_SIGNATURE_TOO_LARGE = "mavenSignatureTooLarge";
  public static final String MAVEN_UPLOAD_BODY_EMPTY = "mavenUploadBodyEmpty";
  public static final String MAVEN_VERSION_TOO_LONG = "mavenVersionTooLong";
  public static final String METHOD_NOT_SUPPORTED = "methodNotSupported";
  public static final String MFA_EXCEPTION = "mfaException";
  public static final String MISSING_REQUEST_HEADER = "missingRequestHeader";
  public static final String MODULE_NOT_FOUND = "moduleNotFound";
  public static final String MODULE_PATH_TOO_LONG = "modulePathTooLong";
  public static final String MODULE_VERSION_TOO_LONG = "moduleVersionTooLong";
  public static final String MODULE_ZIP_ENTRY_NAME_INVALID = "moduleZipEntryNameInvalid";
  public static final String MODULE_ZIP_TOO_LARGE = "moduleZipTooLarge";
  public static final String MODULE_ZIP_TOO_MANY_FILES = "moduleZipTooManyFiles";
  public static final String MOVED_TO_PATH = "movedToPath";
  public static final String NOT_ACCEPTABLE = "notAcceptable";
  public static final String NPM_PUBLISH_BODY_EMPTY = "npmPublishBodyEmpty";
  public static final String NPM_VERSION_STILL_PUBLISHED = "npmVersionStillPublished";
  public static final String NUPKG_NOT_FOUND = "nupkgNotFound";
  public static final String NUSPEC_NOT_FOUND = "nuspecNotFound";
  public static final String PACKAGE_NAME_MISMATCH = "packageNameMismatch";
  public static final String PACKAGE_NAME_TOO_LONG = "packageNameTooLong";
  public static final String PACKAGE_NOT_FOUND = "packageNotFound";
  public static final String PACKAGE_OVERRIDE_DISABLED = "packageOverrideDisabled";
  public static final String PACKAGE_SCOPE_TOO_LONG = "packageScopeTooLong";
  public static final String PACKAGE_VERSION_ALREADY_EXISTS = "packageVersionAlreadyExists";
  public static final String PACKAGE_VERSION_NOT_FOUND = "packageVersionNotFound";
  public static final String PACKAGE_VERSION_TOO_LONG = "packageVersionTooLong";
  public static final String PAGINATION_NUMBER_INVALID = "paginationNumberInvalid";
  public static final String PAYLOAD_TOO_LARGE = "payloadTooLarge";
  public static final String PENDING_SIGNATURE_BYTES_LIMIT_REACHED =
      "pendingSignatureBytesLimitReached";
  public static final String PENDING_SIGNATURE_LIMIT_REACHED = "pendingSignatureLimitReached";
  public static final String PENDING_SIGNATURE_NOT_VERIFIED = "pendingSignatureNotVerified";
  public static final String PGP_PUBLIC_KEY_ALREADY_EXISTS = "pgpPublicKeyAlreadyExists";
  public static final String PGP_PUBLIC_KEY_INVALID = "pgpPublicKeyInvalid";
  public static final String PGP_PUBLIC_KEY_LIMIT_REACHED = "pgpPublicKeyLimitReached";
  public static final String PGP_PUBLIC_KEY_NOT_FOUND = "pgpPublicKeyNotFound";
  public static final String POM_FILE_TOO_LARGE = "pomFileTooLarge";
  public static final String POM_GROUP_ID_MISMATCH = "pomGroupIdMismatch";
  public static final String POM_PACKAGING_TOO_LONG = "pomPackagingTooLong";
  public static final String PYPI_ARCHIVE_EMPTY = "pypiArchiveEmpty";
  public static final String PYPI_ARCHIVE_FILE_NAME_TOO_LONG = "pypiArchiveFileNameTooLong";
  public static final String PYPI_PACKAGE_NAME_TOO_LONG = "pypiPackageNameTooLong";
  public static final String PYPI_REQUIRES_PYTHON_TOO_LONG = "pypiRequiresPythonTooLong";
  public static final String PYPI_VERSION_TOO_LONG = "pypiVersionTooLong";
  public static final String RELEASE_NOT_FOUND = "releaseNotFound";
  public static final String RELEASE_VERSIONS_ARE_PROHIBITED = "releaseVersionsAreProhibited";
  public static final String REPO_NOT_FOUND = "repoNotFound";
  public static final String REPO_SCOPE_NOT_MATCHED = "repoScopeNotMatched";
  public static final String REPO_TYPE_NOT_FOUND = "repoTypeNotFound";
  public static final String RESOURCE_BUSY = "resourceBusy";
  public static final String RESOURCE_NOT_FOUND = "resourceNotFound";
  public static final String SCANNER_DISABLED = "scannerDisabled";
  public static final String SCAN_ALREADY_RUNNING = "scanAlreadyRunning";
  public static final String SCAN_EXECUTOR_SATURATED = "scanExecutorSaturated";
  public static final String SHA256_DIGEST_MISMATCH = "sha256DigestMismatch";
  public static final String SHA256_DIGEST_MISSING = "sha256DigestMissing";
  public static final String SHA256_MISMATCH = "sha256Mismatch";
  public static final String SNAPSHOT_VERSIONS_ARE_PROHIBITED = "snapshotVersionsAreProhibited";
  public static final String TAG_NOT_FOUND = "tagNotFound";
  public static final String TOKEN_NOT_FOUND = "tokenNotFound";
  public static final String TOO_MANY_REQUESTS = "tooManyRequests";
  public static final String UNAUTHORIZED_REQUEST = "unauthorizedRequest";
  public static final String UNKNOWN_PATH = "unknownPath";
  public static final String UNPUBLISH_PAYLOAD_STALE = "unpublishPayloadStale";
  public static final String UNSUPPORTED_MEDIA_TYPE = "unsupportedMediaType";
  public static final String UN_AUTHORIZED = "unAuthorized";
  public static final String URL_VARIABLES_NOT_FOUND = "urlVariablesNotFound";
  public static final String VALIDATION_ERROR = "validationError";
  public static final String VERSION_NOT_FOUND = "versionNotFound";
  public static final String VULNERABILITY_SCAN_NOT_FOUND = "vulnerabilityScanNotFound";
  public static final String WELLKNOWN_KEY_STORE_HOST = "wellknownKeyStoreHost";
}
