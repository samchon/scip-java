package org.scip_code.scip_java.maven;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/** Writes the exact effective reactor selected by the current Maven invocation. */
@Mojo(
    name = "reactor",
    aggregator = true,
    requiresDependencyResolution = ResolutionScope.NONE,
    threadSafe = true)
public final class ReactorWriterMojo extends AbstractMojo {
  private static final String OUTPUT_ENVIRONMENT = "SCIP_GRAPH_MAVEN_REACTOR";

  @Parameter(defaultValue = "${reactorProjects}", required = true, readonly = true)
  private List<MavenProject> projects;

  @Override
  public void execute() throws MojoExecutionException {
    String outputValue = System.getenv(OUTPUT_ENVIRONMENT);
    if (outputValue == null || outputValue.isBlank()) {
      throw new MojoExecutionException("Missing " + OUTPUT_ENVIRONMENT);
    }
    Path output = Paths.get(outputValue).toAbsolutePath().normalize();
    List<String> lines = new ArrayList<>();
    lines.add("schema\t1");
    for (MavenProject project : projects) {
      Path basedir = project.getBasedir().toPath().toAbsolutePath().normalize();
      lines.add("project\t" + encode(basedir));
      for (Object source : project.getCompileSourceRoots()) {
        if (source instanceof String sourcePath) {
          lines.add("source\t" + encode(basedir) + "\t" + encode(Paths.get(sourcePath)));
        }
      }
      for (Object source : project.getTestCompileSourceRoots()) {
        if (source instanceof String sourcePath) {
          lines.add("source\t" + encode(basedir) + "\t" + encode(Paths.get(sourcePath)));
        }
      }
    }

    try {
      Files.createDirectories(output.getParent());
      Path candidate =
          output.resolveSibling(output.getFileName() + ".tmp-" + ProcessHandle.current().pid());
      Files.write(candidate, lines, StandardCharsets.UTF_8);
      try {
        Files.move(
            candidate, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(candidate, output, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException error) {
      throw new MojoExecutionException("Failed to write Maven reactor to " + output, error);
    }
  }

  private static String encode(Path value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(
            value.toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8));
  }
}
