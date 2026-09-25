package io.repsy.e2e.plugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.gradle.api.Plugin;
import org.gradle.api.Project;

/**
 * The one class of the plugin fixture (clients/gradle-plugin.ts): applying it registers a task that
 * prints the marker packed into this very jar, so a test can tell which published jar a build applied.
 */
public class MarkerPlugin implements Plugin<Project> {
  public static final String PREFIX = "E2E-PLUGIN-MARKER:";

  @Override
  public void apply(Project project) {
    String marker = readMarker();
    project
        .getTasks()
        .register("e2eMarker", task -> task.doLast(done -> System.out.println(PREFIX + marker)));
  }

  private static String readMarker() {
    try (InputStream in = MarkerPlugin.class.getResourceAsStream("/e2e-plugin-marker.txt")) {
      if (in == null) {
        throw new IllegalStateException("e2e-plugin-marker.txt is missing from the plugin jar");
      }
      return new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
