package io.repsy.e2e.plugin;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Mojo;

/** The one goal of the plugin the plugin-prefix spec publishes: it logs a marker and needs no project. */
@Mojo(name = "hi", requiresProject = false)
public class HelloMojo extends AbstractMojo {

  @Override
  public void execute() {
    getLog().info("{{{marker}}}");
  }
}
