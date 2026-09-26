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
package io.repsy.protocols.maven.shared.utils;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipInputStream;
import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/**
 * Reads the {@code goalPrefix} of a Maven plugin from the {@code META-INF/maven/plugin.xml} of its
 * jar (RPS-1458). {@code maven-plugin-plugin} writes the descriptor into every plugin jar, and it
 * names the prefix the plugin is run by ({@code mvn <prefix>:<goal>}), which is the {@code
 * goalPrefix} of the plugin's configuration or, when there is none, the one derived from the
 * artifactId.
 *
 * <p>The jar is a client's upload, so the read is bounded and never trusted: at most {@link
 * #MAX_SCANNED_BYTES} of the jar, {@link #MAX_INFLATED_BYTES} inflated and {@link #MAX_ENTRIES}
 * entries are looked at, and only the first {@link #MAX_DESCRIPTOR_BYTES} of the descriptor. The
 * descriptor is parsed with StAX, DTDs and external entities off, and only up to the {@code
 * goalPrefix}, which is the sixth top-level element and so in the first kilobyte, so the (up to a
 * megabyte) list of goals after it is never read. Whatever cannot be read or does not look like a
 * prefix gives {@code null}, and the caller derives one from the artifactId instead.
 */
@Slf4j
@UtilityClass
@NullMarked
public class PluginDescriptorReader {

  public static final String DESCRIPTOR_ENTRY = "META-INF/maven/plugin.xml";

  /** Bytes of the jar itself that are read while looking for the descriptor. */
  public static final long MAX_SCANNED_BYTES = 64L * 1024 * 1024;

  /** Bytes inflated while looking for it: a zip bomb costs time, not memory, and is cut off. */
  public static final long MAX_INFLATED_BYTES = 256L * 1024 * 1024;

  public static final int MAX_ENTRIES = 20_000;

  private static final int DRAIN_BUFFER_BYTES = 8192;

  /** The largest descriptor in a sample of real plugins is 1.1 MB, but its prefix is up front. */
  public static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;

  // What can follow "mvn" in "<prefix>:<goal>".
  private static final Pattern PREFIX = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

  private static final XMLInputFactory XML_FACTORY = xmlFactory();

  /**
   * The valid {@code goalPrefix} of the plugin whose jar is {@code jar}, or {@code null} when the
   * jar has no descriptor, when it cannot be read within the bounds, when the descriptor describes
   * another artifact than {@code expectedArtifactId} (a shaded foreign one) or when the value is
   * not usable as a prefix. Does not close {@code jar}.
   */
  public static @Nullable String goalPrefix(
      final InputStream jar, final String expectedArtifactId) {

    try {
      return validPrefix(scan(new CountingInputStream(jar, MAX_SCANNED_BYTES), expectedArtifactId));
    } catch (final IOException | XMLStreamException e) {
      log.debug("The plugin descriptor of a jar could not be read: {}", e.toString());

      return null;
    }
  }

  private static @Nullable String scan(final InputStream jar, final String expectedArtifactId)
      throws IOException, XMLStreamException {

    final var zip = new ZipInputStream(jar);
    var inflated = 0L;
    var entries = 0;

    for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
      if (DESCRIPTOR_ENTRY.equals(entry.getName())) {
        return readGoalPrefix(
            new LimitedInputStream(zip, MAX_DESCRIPTOR_BYTES), expectedArtifactId);
      }

      inflated += drain(zip, MAX_INFLATED_BYTES - inflated);

      if (++entries > MAX_ENTRIES || inflated > MAX_INFLATED_BYTES) {
        log.debug("A plugin jar is too large to look for its descriptor in");

        return null;
      }
    }

    return null;
  }

  /** Reads the rest of the entry, until it ends or more than {@code budget} bytes were read. */
  private static long drain(final ZipInputStream zip, final long budget) throws IOException {

    final var buffer = new byte[DRAIN_BUFFER_BYTES];
    var total = 0L;
    var read = 0;

    while (total <= budget && read != -1) {
      read = zip.read(buffer);
      total += Math.max(read, 0);
    }

    return total;
  }

  private static @Nullable String readGoalPrefix(
      final InputStream descriptor, final String expectedArtifactId) throws XMLStreamException {

    final XMLStreamReader reader = XML_FACTORY.createXMLStreamReader(descriptor);

    try {
      if (!"plugin".equals(nextChild(reader))) {
        return null;
      }

      var name = nextChild(reader);

      while (name != null && !"mojos".equals(name)) {
        if ("goalPrefix".equals(name)) {
          return reader.getElementText();
        } else if (describesAnotherArtifact(reader, name, expectedArtifactId)) {
          return null;
        }

        name = nextChild(reader);
      }

      return null;
    } finally {
      reader.close();
    }
  }

  /**
   * Moves to the next element that starts at the level below the current one, and returns its name,
   * or {@code null} when that level ends. The first call returns the root element.
   */
  private static @Nullable String nextChild(final XMLStreamReader reader)
      throws XMLStreamException {

    while (reader.hasNext()) {
      final var event = reader.next();

      if (event == XMLStreamConstants.START_ELEMENT) {
        return reader.getLocalName();
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        return null;
      }
    }

    return null;
  }

  /** Reads the {@code artifactId} and skips any other element, which is passed over unread. */
  private static boolean describesAnotherArtifact(
      final XMLStreamReader reader, final String name, final String expectedArtifactId)
      throws XMLStreamException {

    if ("artifactId".equals(name)) {
      return !expectedArtifactId.equals(reader.getElementText().trim());
    }

    var depth = 1;

    while (depth > 0 && reader.hasNext()) {
      final var event = reader.next();

      if (event == XMLStreamConstants.START_ELEMENT) {
        depth++;
      } else if (event == XMLStreamConstants.END_ELEMENT) {
        depth--;
      }
    }

    return false;
  }

  private static @Nullable String validPrefix(final @Nullable String value) {

    if (value == null) {
      return null;
    }

    final var prefix = value.trim();

    return prefix.length() <= MavenPublishLimits.MAX_PREFIX_LENGTH
            && PREFIX.matcher(prefix).matches()
        ? prefix
        : null;
  }

  private static XMLInputFactory xmlFactory() {

    final var factory = XMLInputFactory.newFactory();

    factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
    factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

    // The JDK's parser knows these two, other StAX implementations (Woodstox, on the backend's
    // classpath) do not, and DTDs and external entities are off for them anyway.
    for (final var property :
        List.of(XMLConstants.ACCESS_EXTERNAL_DTD, XMLConstants.ACCESS_EXTERNAL_SCHEMA)) {
      if (factory.isPropertySupported(property)) {
        factory.setProperty(property, "");
      }
    }

    return factory;
  }

  /** Ends the stream, as an {@link IOException}, once more than {@code limit} bytes were read. */
  private static final class CountingInputStream extends FilterInputStream {

    private final long limit;
    private long count;

    CountingInputStream(final InputStream in, final long limit) {
      super(in);
      this.limit = limit;
    }

    @Override
    public int read() throws IOException {
      final var value = super.read();

      if (value != -1) {
        this.add(1);
      }

      return value;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
      final var read = super.read(b, off, len);

      if (read > 0) {
        this.add(read);
      }

      return read;
    }

    private void add(final long bytes) throws IOException {
      this.count += bytes;

      if (this.count > this.limit) {
        throw new IOException("The jar is longer than " + this.limit + " bytes");
      }
    }
  }

  /** Shows the end of the stream after {@code limit} bytes, and never closes the underlying one. */
  private static final class LimitedInputStream extends FilterInputStream {

    private long remaining;

    LimitedInputStream(final InputStream in, final long limit) {
      super(in);
      this.remaining = limit;
    }

    @Override
    public int read() throws IOException {
      if (this.remaining <= 0) {
        return -1;
      }

      final var value = super.read();

      if (value != -1) {
        this.remaining--;
      }

      return value;
    }

    @Override
    public int read(final byte[] b, final int off, final int len) throws IOException {
      if (this.remaining <= 0) {
        return -1;
      }

      final var read = super.read(b, off, (int) Math.min(len, this.remaining));

      if (read > 0) {
        this.remaining -= read;
      }

      return read;
    }

    @Override
    public void close() {
      // The zip stream is not ours to close here: the reader is closed after the entry was read.
    }
  }
}
