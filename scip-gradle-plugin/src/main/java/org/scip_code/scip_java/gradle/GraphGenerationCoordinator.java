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
import org.gradle.api.file.FileCollection;
import org.gradle.api.plugins.ExtraPropertiesExtension;
import org.gradle.api.tasks.compile.JavaCompile;

/** Coordinates task-owned candidates behind one build-wide atomic manifest. */
final class GraphGenerationCoordinator {
  private static final String EXTENSION = "scipJavaGraphGenerationCoordinator";
  private static final String COMMIT_TASK = "scipCommitJavaGraph";

  private final Path targetRoot;
  private final Path sourceRoot;
  private final Task commitTask;
  private final Map<String, Entry> entries = new LinkedHashMap<>();

  private GraphGenerationCoordinator(Project rootProject, Path targetRoot, Path sourceRoot) {
    this.targetRoot = targetRoot.toAbsolutePath().normalize();
    this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
    this.commitTask = rootProject.getTasks().maybeCreate(COMMIT_TASK);
    this.commitTask.doLast(ignored -> commit());
  }

  static GraphGenerationCoordinator acquire(Project project, Path targetRoot, Path sourceRoot) {
    Project root = project.getRootProject();
    ExtraPropertiesExtension extra = root.getExtensions().getExtraProperties();
    if (extra.has(EXTENSION)) {
      GraphGenerationCoordinator coordinator = (GraphGenerationCoordinator) extra.get(EXTENSION);
      coordinator.assertCompatible(targetRoot, sourceRoot);
      return coordinator;
    }
    GraphGenerationCoordinator coordinator =
        new GraphGenerationCoordinator(root, targetRoot, sourceRoot);
    extra.set(EXTENSION, coordinator);
    return coordinator;
  }

  void register(JavaCompile task, GraphGenerationStore store) {
    Entry prior = entries.putIfAbsent(store.targetKey(), new Entry(task.getSource(), store));
    if (prior != null && prior.sources != task.getSource()) {
      throw new IllegalStateException("duplicate Java graph target key " + store.targetKey());
    }
    commitTask.mustRunAfter(task);
  }

  private void commit() {
    try {
      for (Entry entry : entries.values()) {
        if (!entry.store.committedThisBuild()) {
          entry.store.prepare();
          entry.store.commit(entry.sources.getFiles());
        }
      }

      List<ManifestEntry> manifest = new ArrayList<>();
      for (Entry entry : entries.values()) {
        String generation = entry.store.currentGenerationName();
        if (generation != null) {
          manifest.add(new ManifestEntry(entry.store.targetKey(), generation, entry.store));
        }
      }
      manifest.sort(Comparator.comparing(ManifestEntry::targetKey));
      Path storeRoot = targetRoot.resolve("META-INF").resolve("scip-graph-store");
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
      throw new UncheckedIOException("scip-java: unable to publish Java graph manifest", exception);
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
    private final FileCollection sources;
    private final GraphGenerationStore store;

    private Entry(FileCollection sources, GraphGenerationStore store) {
      this.sources = sources;
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

  private record ManifestEntry(String targetKey, String generation, GraphGenerationStore store) {}
}
