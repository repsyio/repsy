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
package io.repsy.protocols.helm.shared.constants;

public final class HelmConstants {

  private HelmConstants() {}

  public static final String API_VERSION = "v1";
  public static final String CHARTS_PATH = "charts";

  /**
   * Where the OCI route stores blobs, keyed by digest. A chart published only through OCI keeps its
   * archive here, under {@code oci/blobs/<digest>}, never under {@link #CHARTS_PATH} (RPS-1217).
   */
  public static final String OCI_BLOBS_PATH = "oci/blobs";

  public static final String INDEX_YAML = "index.yaml";
  public static final String CHART_YAML = "Chart.yaml";
  public static final String TGZ_EXTENSION = ".tgz";
  public static final String CONTENT_TYPE_YAML = "application/yaml";
  public static final String CHART_PART_NAME = "chart";
  public static final String SHA256_PREFIX = "sha256:";

  /**
   * The OCI media type of the Helm chart-content layer (the {@code .tgz}), as opposed to the
   * optional {@code .prov} provenance layer ({@code
   * application/vnd.cncf.helm.chart.provenance.v1.prov}) a signed push adds alongside it. Used to
   * pick the chart layer OUT of a multi-layer manifest by media type rather than by array position
   * (RPS-1719): a real Helm client does not guarantee the chart stays {@code layers[0]} once a
   * {@code .prov} layer is present.
   */
  public static final String CHART_CONTENT_MEDIA_TYPE =
      "application/vnd.cncf.helm.chart.content.v1.tar+gzip";

  /**
   * The most bytes of Chart.yaml the server reads out of an uploaded archive. The archive size is
   * bounded by the upload limit, but a tiny archive can inflate to gigabytes, so the inflated entry
   * is capped as well. Real Chart.yaml files are a few kilobytes.
   */
  public static final long MAX_CHART_YAML_BYTES = 10L * 1024 * 1024;

  // The limits of the varchar columns a pushed chart or OCI object is stored in (RPS-1072).
  // PostgreSQL and H2 create every one of them with exactly this length (V0006__Init_Helm_Support).
  // They are counted in UTF-16 units, the stricter of the two ways either database might count a
  // character, so a value that passes is never refused by the column. The entities take their
  // @Column lengths from here.

  /** {@code helm_chart.name}: the {@code name} of Chart.yaml. */
  public static final int MAX_CHART_NAME_LENGTH = 255;

  /** {@code helm_chart_version.version}. */
  public static final int MAX_CHART_VERSION_LENGTH = 64;

  /** {@code helm_chart_version.app_version}. */
  public static final int MAX_CHART_APP_VERSION_LENGTH = 64;

  /** {@code helm_chart_version.type}. */
  public static final int MAX_CHART_TYPE_LENGTH = 32;

  /** {@code helm_chart_version.api_version}: Helm knows {@code v1} and {@code v2}. */
  public static final int MAX_CHART_API_VERSION_LENGTH = 32;

  /** The {@code apiVersion} of a chart whose Chart.yaml has none: Helm's loader reads it as v1. */
  public static final String DEFAULT_CHART_API_VERSION = "v1";

  /**
   * The most bytes of the {@code dependencies} of a Chart.yaml that are kept (as JSON, in {@code
   * helm_chart_version.dependencies}, a {@code text} column). Every version's dependencies are
   * repeated in {@code index.yaml}, so a chart cannot make the index arbitrarily large. Real charts
   * declare a few short entries.
   */
  public static final int MAX_CHART_DEPENDENCIES_BYTES = 256 * 1024;

  /**
   * {@code helm_chart_version.digest}, {@code helm_oci_blob.digest} and {@code
   * helm_oci_manifest.digest}: {@code sha256:} and 64 hex characters. The push handlers only let a
   * sha256 digest reach these columns.
   */
  public static final int MAX_DIGEST_LENGTH = 71;

  /** {@code helm_oci_manifest.name}: the name in the push path, which must equal the chart's. */
  public static final int MAX_OCI_MANIFEST_NAME_LENGTH = 255;

  /** {@code helm_oci_manifest.reference}: the tag or digest in the push path. */
  public static final int MAX_OCI_MANIFEST_REFERENCE_LENGTH = 255;

  /** {@code helm_oci_manifest.media_type}, and {@code helm_oci_blob.media_type}. */
  public static final int MAX_OCI_MEDIA_TYPE_LENGTH = 255;
}
