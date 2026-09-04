package org.scip_code.scip_java.gradle;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.gradle.api.Project;
import org.gradle.api.Task;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.tasks.TaskProvider;

/** Coordinates task-owned candidates behind one build-wide atomic manifest. */
final class KotlinGraphGenerationCoordinator {
  private static final String EXTENSION = "scipKotlinGraphGenerationCoordinator";
  static final String COMMIT_TASK = "samchonCommitKotlinGraph";

  private final Path targetRoot;
  private final Path sourceRoot;
  private final Task commitTask;
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  private KotlinGraphGenerationCoordinator(Project rootProject, Path targetRoot, Path sourceRoot) {
    this.targetRoot = targetRoot.toAbsolutePath().normalize();
    this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
    this.commitTask = rootProject.getTasks().maybeCreate(COMMIT_TASK);
    this.commitTask.doLast(ignored -> commit());
  }

  static KotlinGraphGenerationCoordinator acquire(
      Project project, Path targetRoot, Path sourceRoot) {
    Project root = project.getRootProject();
    ExtraPropertiesExtension extra = root.getExtensions().getExtraProperties();
    if (extra.has(EXTENSION)) {
      KotlinGraphGenerationCoordinator coordinator =
          (KotlinGraphGenerationCoordinator) extra.get(EXTENSION);
      coordinator.assertCompatible(targetRoot, sourceRoot);
      return coordinator;
    }
    KotlinGraphGenerationCoordinator coordinator =
        new KotlinGraphGenerationCoordinator(root, targetRoot, sourceRoot);
    extra.set(EXTENSION, coordinator);
    return coordinator;
  }

  void register(TaskProvider<? extends Task> task, KotlinGraphGenerationStore store) {
    Entry prior = entries.putIfAbsent(store.targetKey(), new Entry(store));
    if (prior != null) {
      throw new IllegalStateException("duplicate Kotlin graph target key " + store.targetKey());
    }
    commitTask.dependsOn(task);
    commitTask.mustRunAfter(task);
  }

  private void commit() {
    try {
      List<ManifestEntry> manifest = new ArrayList<>();
      for (Entry entry : entries.values()) {
        String generation = entry.store.currentGenerationName();
        if (generation != null) {
          manifest.add(new ManifestEntry(entry.store.targetKey(), generation, entry.store));
        }
      }
      manifest.sort(Comparator.comparing(ManifestEntry::targetKey));
      Path storeRoot = targetRoot.resolve("META-INF").resolve("kotlin-graph-store");
      Files.createDirectories(storeRoot);
      Path output = storeRoot.resolve("MANIFEST");
      Path temporary = output.resolveSibling("MANIFEST.tmp-" + ProcessHandle.current().pid());
      StringBuilder text = new StringBuilder();
      for (ManifestEntry entry : manifest) {
        text.append(entry.targetKey()).append(' ').append(entry.generation()).append('\n');
      }
      Files.writeString(temporary, text, StandardCharsets.UTF_8);
      move(temporary, output);
      for (ManifestEntry entry : manifest) {
        entry.store().pruneRetaining(entry.generation());
      }
      pruneRemovedTargets(
          storeRoot.resolve("targets"),
          manifest.stream().map(ManifestEntry::targetKey).collect(Collectors.toSet()));
    } catch (IOException exception) {
      throw new UncheckedIOException(
          "scip-java: unable to publish Kotlin graph manifest", exception);
    }
  }

  private void assertCompatible(Path targetRoot, Path sourceRoot) {
    if (!this.targetRoot.equals(targetRoot.toAbsolutePath().normalize())
        || !this.sourceRoot.equals(sourceRoot.toAbsolutePath().normalize())) {
      throw new IllegalStateException("scip-java: inconsistent Gradle graph roots");
    }
  }

  private static void move(Path source, Path destination) throws IOException {
    try {
      Files.move(
          source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static final class Entry {
    private final KotlinGraphGenerationStore store;

    private Entry(KotlinGraphGenerationStore store) {
      this.store = store;
    }
  }

  private static void pruneRemovedTargets(Path targetsRoot, Set<String> retained)
      throws IOException {
    if (!Files.isDirectory(targetsRoot)) return;
    try (var paths = Files.list(targetsRoot)) {
      for (Path target : paths.filter(Files::isDirectory).toList()) {
        if (!retained.contains(target.getFileName().toString())) deleteTree(target);
      }
    }
  }

  private static void deleteTree(Path root) throws IOException {
    Files.walkFileTree(
        root,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path directory, IOException exception)
              throws IOException {
            if (exception != null) throw exception;
            Files.delete(directory);
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private record ManifestEntry(
      String targetKey, String generation, KotlinGraphGenerationStore store) {}
}
