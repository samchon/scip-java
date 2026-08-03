package org.scip_code.scip_java.gradle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.gradle.api.DefaultTask;
import org.gradle.api.Project;
import org.gradle.api.artifacts.component.ModuleComponentIdentifier;
import org.gradle.api.artifacts.result.ResolvedArtifactResult;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.publish.PublishingExtension;
import org.gradle.api.publish.maven.MavenPublication;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.TaskAction;

public abstract class WriteDependencies extends DefaultTask {

  @Input
  public abstract ListProperty<String> getDependencies();

  @OutputFile
  public abstract RegularFileProperty getOutputFile();

  static void configure(Project project, WriteDependencies task, Path depsOut) {
    String projectPath = project.getPath().replaceAll("[^a-z0-9A-Z_-]", "_");
    Path output =
        depsOut.getFileName().toString().endsWith("dependencies.txt")
            ? depsOut.resolveSibling(projectPath + "." + depsOut.getFileName())
            : depsOut;
    task.getOutputFile().set(output.toFile());

    project.afterEvaluate(ignored -> addPublications(project, task));
    project
        .getConfigurations()
        .configureEach(
            configuration -> {
              if (!configuration.isCanBeResolved()) return;
              var artifacts =
                  configuration
                      .getIncoming()
                      .artifactView(view -> view.lenient(true))
                      .getArtifacts();
              task.getDependencies()
                  .addAll(
                      artifacts
                          .getResolvedArtifacts()
                          .map(
                              resolved ->
                                  resolved.stream()
                                      .map(WriteDependencies::dependencyLine)
                                      .filter(line -> line != null)
                                      .sorted()
                                      .toList()));
            });
  }

  private static void addPublications(Project project, WriteDependencies task) {
    List<String> publications = new ArrayList<>();
    PublishingExtension publishing = project.getExtensions().findByType(PublishingExtension.class);
    SourceSetContainer sourceSets = project.getExtensions().findByType(SourceSetContainer.class);
    if (publishing == null || sourceSets == null) return;
    SourceSet main = sourceSets.findByName("main");
    if (main == null) return;
    String classesDirectory =
        main.getOutput().getClassesDirs().getFiles().stream()
            .map(file -> file.getAbsolutePath())
            .sorted()
            .findFirst()
            .orElse(null);
    if (classesDirectory == null) return;
    for (MavenPublication publication :
        publishing.getPublications().withType(MavenPublication.class)) {
      publications.add(
          String.join(
              "\t",
              publication.getGroupId(),
              publication.getArtifactId(),
              publication.getVersion(),
              classesDirectory));
    }
    task.getDependencies().addAll(publications);
  }

  private static String dependencyLine(ResolvedArtifactResult artifact) {
    if (!(artifact.getId().getComponentIdentifier() instanceof ModuleComponentIdentifier module))
      return null;
    return String.join(
        "\t",
        module.getGroup(),
        module.getModule(),
        module.getVersion(),
        artifact.getFile().getAbsolutePath());
  }

  @TaskAction
  public void printResolvedDependencies() throws IOException {
    Path output = getOutputFile().get().getAsFile().toPath();
    Files.createDirectories(output.getParent());
    List<String> dependencies =
        getDependencies().get().stream().distinct().sorted(Comparator.naturalOrder()).toList();
    String text = dependencies.isEmpty() ? "" : String.join("\n", dependencies) + "\n";
    Files.writeString(output, text);
  }
}
