package org.scip_code.scip_java.gradle;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import org.gradle.api.GradleException;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilation;
import org.jetbrains.kotlin.gradle.plugin.KotlinCompilerPluginSupportPlugin;
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType;
import org.jetbrains.kotlin.gradle.plugin.SubpluginArtifact;
import org.jetbrains.kotlin.gradle.plugin.SubpluginOption;
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask;

/**
 * Attaches the pinned K2 graph exporter to ordinary Kotlin/JVM compilations.
 *
 * <p>This support plugin does not create a compiler task. Its build-wide commit task depends on the
 * Kotlin Gradle plugin's existing compilation tasks and publishes only generations from successful
 * task executions.
 */
public final class KotlinGraphGradlePlugin implements KotlinCompilerPluginSupportPlugin {
  static final String KOTLIN_VERSION = "2.3.20";
  static final String ARTIFACT_GROUP = "org.scip-code";
  static final String ARTIFACT_NAME = "scip-kotlinc-k2-graph";
  static final String ARTIFACT_VERSION = "2.3.20-e940c188";

  private Project project;
  private Path sourceRoot;
  private Path targetRoot;
  private Path compilerPlugin;
  private KotlinGraphGenerationCoordinator coordinator;

  @Override
  public void apply(Project project) {
    this.project = project;
    Map<String, Object> extra = project.getExtensions().getExtraProperties().getProperties();
    this.sourceRoot = project.getRootDir().toPath().toAbsolutePath().normalize();
    this.targetRoot =
        Paths.get(requiredExtra(extra, "scipTarget").toString()).toAbsolutePath().normalize();
    this.compilerPlugin =
        Paths.get(requiredExtra(extra, "scipKotlincGraphJar").toString())
            .toAbsolutePath()
            .normalize();
    Path compilerRepository =
        Paths.get(requiredExtra(extra, "scipKotlincGraphRepository").toString())
            .toAbsolutePath()
            .normalize();
    try {
      project.getRepositories().maven(repository -> repository.setUrl(compilerRepository.toUri()));
    } catch (GradleException rejectedProjectRepository) {
      project
          .getLogger()
          .info(
              "scip-java: using the settings-level Kotlin graph repository for project '{}'",
              project.getPath());
    }

    Plugin<?> kotlinPlugin = project.getPlugins().findPlugin("org.jetbrains.kotlin.jvm");
    String version =
        kotlinPlugin == null
            ? null
            : kotlinPlugin.getClass().getPackage().getImplementationVersion();
    if (version == null
        || !(version.equals(KOTLIN_VERSION) || version.startsWith(KOTLIN_VERSION + "-release-"))) {
      throw new GradleException(
          "scip-java: Kotlin graph exporter supports Kotlin Gradle Plugin "
              + KOTLIN_VERSION
              + " exactly; project '"
              + project.getPath()
              + "' uses "
              + String.valueOf(version));
    }
    this.coordinator = KotlinGraphGenerationCoordinator.acquire(project, targetRoot, sourceRoot);
  }

  @Override
  public boolean isApplicable(KotlinCompilation<?> compilation) {
    return compilation.getPlatformType() == KotlinPlatformType.jvm;
  }

  @Override
  public Provider<List<SubpluginOption>> applyToCompilation(KotlinCompilation<?> compilation) {
    String targetName = targetName(compilation);
    String target = project.getPath() + "|" + targetName + "|" + compilation.getCompilationName();
    KotlinGraphGenerationStore store =
        new KotlinGraphGenerationStore(targetRoot, sourceRoot, target, compilerPlugin);
    TaskProvider<? extends KotlinCompilationTask<?>> compileTask =
        compilation.getCompileTaskProvider();
    coordinator.register(compileTask, store);
    List<String> compilationUniverse =
        new java.util.ArrayList<>(
            List.of(
                "gradle.version=" + project.getGradle().getGradleVersion(),
                "project=" + project.getPath(),
                "task="
                    + (project.getPath().equals(":") ? ":" : project.getPath() + ":")
                    + compileTask.getName(),
                "target=" + targetName,
                "compilation=" + compilation.getCompilationName(),
                "platform=" + compilation.getPlatformType().getName()));
    compilation.getAllKotlinSourceSets().stream()
        .map(sourceSet -> "sourceSet=" + sourceSet.getName())
        .sorted(KotlinGraphGenerationStore::compareUtf8)
        .forEach(compilationUniverse::add);
    compileTask.configure(
        task -> {
          Task gradleTask = (Task) task;
          gradleTask.getOutputs().dir(store.outputRoot().toFile());
          gradleTask.doFirst(ignored -> store.prepare());
          gradleTask.doLast(
              ignored ->
                  store.commit(
                      store.kotlinSources(gradleTask),
                      store.universe(gradleTask, compilationUniverse)));
        });

    Path scipTargetRoot = targetRoot;
    Path staging = store.staging();
    return project.provider(
        () ->
            List.of(
                new SubpluginOption("sourceroot", sourceRoot.toString()),
                new SubpluginOption("targetroot", scipTargetRoot.toString()),
                new SubpluginOption("graphroot", staging.toString()),
                new SubpluginOption("graphtarget", target)));
  }

  @Override
  public String getCompilerPluginId() {
    return "scip-kotlinc";
  }

  @Override
  public SubpluginArtifact getPluginArtifact() {
    return new SubpluginArtifact(ARTIFACT_GROUP, ARTIFACT_NAME, ARTIFACT_VERSION);
  }

  @Override
  @SuppressWarnings("deprecation")
  public SubpluginArtifact getPluginArtifactForNative() {
    return null;
  }

  private static String targetName(KotlinCompilation<?> compilation) {
    String name = compilation.getTarget().getTargetName();
    return name.isBlank() ? compilation.getPlatformType().getName() : name;
  }

  private static Object requiredExtra(Map<String, Object> extra, String name) {
    Object value = extra.get(name);
    if (value == null) {
      throw new IllegalStateException(
          name + " extra property must be set by the scip-java Gradle init script");
    }
    return value;
  }
}
