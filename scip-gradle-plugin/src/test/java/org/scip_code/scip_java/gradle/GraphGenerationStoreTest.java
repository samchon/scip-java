package org.scip_code.scip_java.gradle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GraphGenerationStoreTest {
  @Test
  void currentValidityForcesCompilationWhenTheGraphIsMissingOrStale(@TempDir Path root)
      throws IOException {
    Path project = root.resolve("project");
    Path source = project.resolve("src/A.java");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "");
    GraphGenerationStore store =
        new GraphGenerationStore(root.resolve("target"), project, ":app:compileJava");
    store.prepare();
    replace(
        store.staging().resolve("src/A.java.graph.json"),
        shard("src/A.java")
            .replace(
                "\"diskDigest\":\"\"",
                "\"diskDigest\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\""));
    marker(store.staging(), "src/A.java");
    store.commit(Set.of(source.toFile()), List.of("universe"));

    assertTrue(store.matchesCurrent(Set.of(source.toFile()), List.of("universe")));
    assertFalse(store.matchesCurrent(Set.of(source.toFile()), List.of("moved")));
    Files.writeString(source, "class Moved {}\n");
    assertFalse(store.matchesCurrent(Set.of(source.toFile()), List.of("universe")));
    Files.delete(store.outputRoot().resolve("CURRENT"));
    assertFalse(store.matchesCurrent(Set.of(source.toFile()), List.of("universe")));
  }

  @Test
  void commitsSuccessfulTasksAndLeavesFailedStagingInvisible(@TempDir Path root)
      throws IOException {
    Path sources = root.resolve("project");
    Path target = root.resolve("target");
    Path firstSource = sources.resolve("src/A.java");
    Path secondSource = sources.resolve("src/B.java");
    GraphGenerationStore store = new GraphGenerationStore(target, sources, ":app:compileJava");

    store.prepare();
    replace(store.staging().resolve("src/A.java.graph.json"), shard("src/A.java"));
    marker(store.staging(), "src/A.java");
    store.commit(Set.of(firstSource.toFile()));
    String firstGeneration = current(store.outputRoot());
    assertTrue(
        committed(store.outputRoot(), firstGeneration)
            .resolve("src/A.java.graph.json")
            .toFile()
            .isFile());

    store.prepare();
    replace(store.staging().resolve("src/A.java.graph.json"), "broken candidate\n");
    assertThrows(RuntimeException.class, () -> store.commit(Set.of(firstSource.toFile())));
    assertEquals(firstGeneration, current(store.outputRoot()));
    assertFalse(
        Files.readString(
                committed(store.outputRoot(), firstGeneration).resolve("src/A.java.graph.json"))
            .contains("broken"));

    store.prepare();
    replace(store.staging().resolve("src/B.java.graph.json"), shard("src/B.java"));
    marker(store.staging(), "src/B.java");
    store.commit(Set.of(secondSource.toFile()));
    String secondGeneration = current(store.outputRoot());
    assertNotEquals(firstGeneration, secondGeneration);
    Path second = committed(store.outputRoot(), secondGeneration);
    assertFalse(Files.exists(second.resolve("src/A.java.graph.json")));
    assertTrue(Files.isRegularFile(second.resolve("src/B.java.graph.json")));
    assertTrue(Files.exists(committed(store.outputRoot(), firstGeneration)));
    store.pruneRetaining(secondGeneration);
    assertFalse(Files.exists(committed(store.outputRoot(), firstGeneration)));

    store.prepare();
    store.commit(Set.of(secondSource.toFile()));
    assertEquals(secondGeneration, current(store.outputRoot()));

    store.prepare();
    store.commit(Set.of());
    String emptyGeneration = current(store.outputRoot());
    assertNotEquals(secondGeneration, emptyGeneration);
    Path empty = committed(store.outputRoot(), emptyGeneration);
    assertEquals("", Files.readString(empty.resolve("SOURCES")));
    assertFalse(Files.exists(empty.resolve("src/B.java.graph.json")));
  }

  @Test
  void preservesExistingGeneratedSourcesAcrossAnUpToDateTask(@TempDir Path root)
      throws IOException {
    Path sources = root.resolve("project");
    Path target = root.resolve("target");
    Path input = sources.resolve("src/A.java");
    Path generated = sources.resolve("build/generated/Generated.java");
    Files.createDirectories(input.getParent());
    Files.writeString(input, "class A {}\n");
    Files.createDirectories(generated.getParent());
    Files.writeString(generated, "class Generated {}\n");
    GraphGenerationStore store = new GraphGenerationStore(target, sources, ":compileJava");

    store.prepare();
    replace(store.staging().resolve("src/A.java.graph.json"), shard("src/A.java", ":compileJava"));
    replace(
        store.staging().resolve("build/generated/Generated.java.graph.json"),
        shard("build/generated/Generated.java", ":compileJava"));
    marker(store.staging(), "src/A.java");
    marker(store.staging(), "build/generated/Generated.java");
    store.commit(Set.of(input.toFile()));

    store.prepare();
    store.commit(Set.of(input.toFile()));
    String noOpGeneration = current(store.outputRoot());
    assertTrue(
        Files.isRegularFile(
            committed(store.outputRoot(), noOpGeneration)
                .resolve("build/generated/Generated.java.graph.json")));

    Files.delete(generated);
    store.prepare();
    store.commit(Set.of(input.toFile()));
    String deletedGeneration = current(store.outputRoot());
    assertFalse(
        Files.exists(
            committed(store.outputRoot(), deletedGeneration)
                .resolve("build/generated/Generated.java.graph.json")));
  }

  @Test
  void removesADeclaredSourceThatLeavesTheTaskWithoutDeletingIt(@TempDir Path root)
      throws IOException {
    Path sources = root.resolve("project");
    Path target = root.resolve("target");
    Path excluded = sources.resolve("src/Excluded.java");
    Files.createDirectories(excluded.getParent());
    Files.writeString(excluded, "class Excluded {}\n");
    GraphGenerationStore store = new GraphGenerationStore(target, sources, ":compileJava");

    store.prepare();
    replace(
        store.staging().resolve("src/Excluded.java.graph.json"),
        shard("src/Excluded.java", ":compileJava"));
    marker(store.staging(), "src/Excluded.java");
    store.commit(Set.of(excluded.toFile()));

    store.prepare();
    store.commit(Set.of());
    Path committed = committed(store.outputRoot(), current(store.outputRoot()));
    assertTrue(Files.isRegularFile(excluded));
    assertFalse(Files.exists(committed.resolve("src/Excluded.java.graph.json")));
  }

  @Test
  void externalTaskInputsUseContentIdentityInsteadOfTemporaryDirectories(@TempDir Path root)
      throws IOException {
    Path first = root.resolve("first-random/scip-plugin.jar");
    Path second = root.resolve("second-random/scip-plugin.jar");
    Files.createDirectories(first.getParent());
    Files.createDirectories(second.getParent());
    Files.writeString(first, "same embedded plugin\n");
    Files.writeString(second, "same embedded plugin\n");
    GraphGenerationStore firstStore =
        new GraphGenerationStore(
            root.resolve("target"), root.resolve("project"), ":compileJava", first);
    GraphGenerationStore secondStore =
        new GraphGenerationStore(
            root.resolve("target"), root.resolve("project"), ":compileJava", second);

    assertEquals(firstStore.universeInput(first), secondStore.universeInput(second));
    Files.writeString(second, "different embedded plugin\n");
    assertNotEquals(firstStore.universeInput(first), secondStore.universeInput(second));
  }

  @Test
  void ordinarySameNamedInputsRetainTheirPathToContentAssociation(@TempDir Path root)
      throws IOException {
    GraphGenerationStore store =
        new GraphGenerationStore(root.resolve("target"), root.resolve("project"), ":compileJava");
    Path first = root.resolve("cache-a/library.jar");
    Path second = root.resolve("cache-b/library.jar");
    Files.createDirectories(first.getParent());
    Files.createDirectories(second.getParent());
    Files.writeString(first, "first\n");
    Files.writeString(second, "second\n");

    Set<String> before = Set.of(store.universeInput(first), store.universeInput(second));
    Files.writeString(first, "second\n");
    Files.writeString(second, "first\n");
    Set<String> after = Set.of(store.universeInput(first), store.universeInput(second));

    assertNotEquals(before, after);
  }

  private static void marker(Path staging, String source) throws IOException {
    Path marker = staging.resolve(".seen").resolve(source + ".seen");
    Files.createDirectories(marker.getParent());
    Files.writeString(marker, "");
  }

  private static void replace(Path output, String text) throws IOException {
    Files.createDirectories(output.getParent());
    Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
    Files.writeString(temporary, text);
    Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
  }

  private static String shard(String source) {
    return shard(source, ":app:compileJava");
  }

  private static String shard(String source, String target) {
    return "{\"schemaVersion\":1,\"source\":\""
        + source
        + "\",\"target\":\""
        + target
        + "\",\"checkerDigest\":\"e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\",\"diskDigest\":\"\"}\n";
  }

  private static String current(Path outputRoot) throws IOException {
    return Files.readString(outputRoot.resolve("CURRENT")).trim();
  }

  private static Path committed(Path outputRoot, String generation) {
    return outputRoot.resolve("generations").resolve(generation);
  }
}
