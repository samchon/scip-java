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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import kotlinx.serialization.json.Json;
import kotlinx.serialization.json.JsonElement;
import kotlinx.serialization.json.JsonElementKt;
import kotlinx.serialization.json.JsonObject;
import kotlinx.serialization.json.JsonPrimitive;
import org.gradle.api.tasks.compile.JavaCompile;

/** Task-owned immutable graph generations with an atomically replaced current pointer. */
final class GraphGenerationStore {
  private static final String SHARD_SUFFIX = ".graph.json";
  private static final String SEEN_ROOT = ".seen";
  private static final java.util.regex.Pattern SHA256 =
      java.util.regex.Pattern.compile("[0-9a-f]{64}");

  private final Path sourceRoot;
  private final String target;
  private final String targetKey;
  private final Path storeRoot;
  private final Path outputRoot;
  private final Path staging;
  private final Path generations;
  private final Path current;
  private boolean committedThisBuild;

  GraphGenerationStore(Path targetRoot, Path sourceRoot, String target) {
    this.sourceRoot = sourceRoot.toAbsolutePath().normalize();
    this.target = target;
    this.targetKey = digest(target);
    this.storeRoot =
        targetRoot.toAbsolutePath().normalize().resolve("META-INF").resolve("scip-graph-store");
    this.outputRoot = storeRoot.resolve("targets").resolve(targetKey);
    this.staging = outputRoot.resolve("staging");
    this.generations = outputRoot.resolve("generations");
    this.current = outputRoot.resolve("CURRENT");
  }

  Path staging() {
    return staging;
  }

  Path outputRoot() {
    return outputRoot;
  }

  String targetKey() {
    return targetKey;
  }

  /** Start from the prior committed generation; no published pointer changes here. */
  void prepare() {
    try {
      deleteTree(staging);
      Files.createDirectories(staging);
      Path prior = currentGeneration();
      if (prior != null) copyTree(prior, staging);
      deleteTree(staging.resolve(SEEN_ROOT));
      Files.createDirectories(staging.resolve(SEEN_ROOT));
    } catch (IOException exception) {
      throw new UncheckedIOException("scip-java: unable to prepare graph generation", exception);
    }
  }

  /** Commit only after Gradle reports that the JavaCompile task completed successfully. */
  void commit(Set<java.io.File> taskSources) {
    commit(taskSources, null);
  }

  void commit(Set<java.io.File> taskSources, List<String> universe) {
    try {
      Set<String> active = new LinkedHashSet<>();
      for (java.io.File source : taskSources) {
        active.add(relativeSource(source.toPath().toAbsolutePath().normalize()));
      }
      Path seen = staging.resolve(SEEN_ROOT);
      if (Files.isDirectory(seen)) {
        try (var paths = Files.walk(seen)) {
          paths
              .filter(Files::isRegularFile)
              .map(seen::relativize)
              .map(Path::toString)
              .map(value -> value.replace(java.io.File.separatorChar, '/'))
              .map(value -> value.substring(0, value.length() - ".seen".length()))
              .forEach(active::add);
        }
      }

      List<Path> shards = graphShards(staging);
      for (Path shard : shards) {
        String source = shardSource(staging.relativize(shard));
        if (!active.contains(source) && !sourceExists(source)) Files.deleteIfExists(shard);
      }
      deleteEmptyDirectories(staging);
      deleteTree(staging.resolve(SEEN_ROOT));
      writeAtomic(staging.resolve("TARGET"), List.of(target));
      List<String> orderedSources = new ArrayList<>();
      for (Path shard : graphShards(staging)) {
        ShardMetadata metadata = shardMetadata(shard);
        String expectedSource = shardSource(staging.relativize(shard));
        if (!metadata.source().equals(expectedSource)) {
          throw new IOException("Java graph shard source does not match its path: " + shard);
        }
        if (!metadata.target().equals(target)) {
          throw new IOException("Java graph shard target does not match its task: " + shard);
        }
        validateSource(metadata, shard);
        orderedSources.add(metadata.source());
      }
      orderedSources = new ArrayList<>(new LinkedHashSet<>(orderedSources));
      orderedSources.sort(GraphGenerationStore::compareUtf8);
      writeAtomic(staging.resolve("SOURCES"), orderedSources);
      if (universe != null) writeAtomic(staging.resolve("UNIVERSE"), universe);
      if (!Files.isRegularFile(staging.resolve("UNIVERSE"))) {
        writeAtomic(
            staging.resolve("UNIVERSE"),
            List.of("java.version=" + System.getProperty("java.version", "")));
      }

      String generation = generationDigest(staging);
      Files.createDirectories(generations);
      Path committed = generations.resolve(generation);
      if (Files.exists(committed)) {
        deleteTree(staging);
      } else {
        move(staging, committed, false);
      }

      Files.createDirectories(current.getParent());
      Path temporary = current.resolveSibling("CURRENT.tmp-" + ProcessHandle.current().pid());
      Files.writeString(temporary, generation + "\n", StandardCharsets.UTF_8);
      move(temporary, current, true);
      committedThisBuild = true;
    } catch (IOException exception) {
      throw new UncheckedIOException("scip-java: unable to commit graph generation", exception);
    }
  }

  List<String> universe(JavaCompile task) {
    try {
      List<String> rows = new ArrayList<>();
      rows.add("java.version=" + System.getProperty("java.version", ""));
      rows.add("java.home=" + normalizedPath(Path.of(System.getProperty("java.home", ""))));
      rows.add("sourceCompatibility=" + task.getSourceCompatibility());
      rows.add("targetCompatibility=" + task.getTargetCompatibility());
      rows.add("encoding=" + String.valueOf(task.getOptions().getEncoding()));
      Integer release = task.getOptions().getRelease().getOrNull();
      rows.add("release=" + String.valueOf(release));
      List<?> compilerArgs = task.getOptions().getCompilerArgs();
      for (int index = 0; index < compilerArgs.size(); index++) {
        rows.add("compilerArg[" + index + "]=" + String.valueOf(compilerArgs.get(index)));
      }
      List<Path> inputs =
          task.getInputs().getFiles().getFiles().stream()
              .map(java.io.File::toPath)
              .map(Path::toAbsolutePath)
              .map(Path::normalize)
              .sorted(Comparator.comparing(this::normalizedPath, GraphGenerationStore::compareUtf8))
              .toList();
      for (Path input : inputs) {
        rows.add("input=" + normalizedPath(input) + ":" + fileDigest(input));
      }
      return rows;
    } catch (IOException exception) {
      throw new UncheckedIOException("scip-java: unable to capture Java graph universe", exception);
    }
  }

  String currentGenerationName() throws IOException {
    Path generation = currentGeneration();
    return generation == null ? null : generation.getFileName().toString();
  }

  boolean committedThisBuild() {
    return committedThisBuild;
  }

  void pruneRetaining(String generation) throws IOException {
    pruneGenerations(generation);
  }

  private Path currentGeneration() throws IOException {
    if (!Files.isRegularFile(current)) return null;
    String generation = Files.readString(current, StandardCharsets.UTF_8).trim();
    if (!generation.matches("[0-9a-f]{64}")) {
      throw new IOException("invalid graph CURRENT pointer for " + target);
    }
    Path resolved = generations.resolve(generation).normalize();
    if (!resolved.startsWith(generations) || !Files.isDirectory(resolved)) {
      throw new IOException("graph CURRENT pointer names no committed generation for " + target);
    }
    return resolved;
  }

  private String relativeSource(Path source) {
    Path relative = source.startsWith(sourceRoot) ? sourceRoot.relativize(source) : source;
    StringBuilder out = new StringBuilder();
    for (Path part : relative) {
      if (!out.isEmpty()) out.append('/');
      out.append(part.getFileName());
    }
    return out.toString();
  }

  private boolean sourceExists(String source) {
    try {
      Path path = Path.of(source);
      Path absolute = path.isAbsolute() ? path.normalize() : sourceRoot.resolve(path).normalize();
      return Files.isRegularFile(absolute);
    } catch (RuntimeException ignored) {
      return false;
    }
  }

  private String normalizedPath(Path path) {
    Path normalized = path.toAbsolutePath().normalize();
    Path value = normalized.startsWith(sourceRoot) ? sourceRoot.relativize(normalized) : normalized;
    return value.toString().replace(java.io.File.separatorChar, '/');
  }

  private static String fileDigest(Path input) throws IOException {
    MessageDigest digest = sha256();
    if (Files.isRegularFile(input)) {
      update(digest, Files.readAllBytes(input));
    } else if (Files.isDirectory(input)) {
      try (var paths = Files.walk(input)) {
        for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
          update(
              digest,
              input
                  .relativize(file)
                  .toString()
                  .replace(java.io.File.separatorChar, '/')
                  .getBytes(StandardCharsets.UTF_8));
          update(digest, Files.readAllBytes(file));
        }
      }
    } else {
      update(digest, "<missing>".getBytes(StandardCharsets.UTF_8));
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static String shardSource(Path relative) {
    String value = relative.toString().replace(java.io.File.separatorChar, '/');
    return value.substring(0, value.length() - SHARD_SUFFIX.length());
  }

  private static ShardMetadata shardMetadata(Path shard) throws IOException {
    try {
      JsonElement parsed =
          Json.Default.parseToJsonElement(Files.readString(shard, StandardCharsets.UTF_8));
      if (!(parsed instanceof JsonObject object)) {
        throw new IOException("Java graph shard is not an object: " + shard);
      }
      JsonPrimitive schema = JsonElementKt.getJsonPrimitive(object.get("schemaVersion"));
      String source = JsonElementKt.getJsonPrimitive(object.get("source")).getContent();
      String target = JsonElementKt.getJsonPrimitive(object.get("target")).getContent();
      String checkerDigest =
          JsonElementKt.getJsonPrimitive(object.get("checkerDigest")).getContent();
      String diskDigest = JsonElementKt.getJsonPrimitive(object.get("diskDigest")).getContent();
      if (!Integer.valueOf(1).equals(JsonElementKt.getIntOrNull(schema))
          || source.isEmpty()
          || target.isEmpty()
          || !SHA256.matcher(checkerDigest).matches()
          || (!diskDigest.isEmpty() && !SHA256.matcher(diskDigest).matches())) {
        throw new IOException("Java graph shard has invalid metadata: " + shard);
      }
      return new ShardMetadata(source, target, diskDigest);
    } catch (IOException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new IOException("malformed Java graph shard: " + shard, exception);
    }
  }

  private void validateSource(ShardMetadata metadata, Path shard) throws IOException {
    if (metadata.diskDigest().isEmpty()) return;
    Path source = sourceRoot.resolve(metadata.source()).normalize();
    if (!source.startsWith(sourceRoot)
        || !Files.isRegularFile(source)
        || !digest(Files.readAllBytes(source)).equals(metadata.diskDigest())) {
      throw new IOException("Java graph source moved after compilation: " + shard);
    }
  }

  private static List<Path> graphShards(Path root) throws IOException {
    if (!Files.isDirectory(root)) return List.of();
    try (var paths = Files.walk(root)) {
      return paths
          .filter(Files::isRegularFile)
          .filter(path -> path.getFileName().toString().endsWith(SHARD_SUFFIX))
          .sorted()
          .toList();
    }
  }

  private static String generationDigest(Path root) throws IOException {
    MessageDigest digest = sha256();
    try (var paths = Files.walk(root)) {
      for (Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
        String relative = root.relativize(file).toString().replace(java.io.File.separatorChar, '/');
        update(digest, relative.getBytes(StandardCharsets.UTF_8));
        update(digest, Files.readAllBytes(file));
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static void update(MessageDigest digest, byte[] value) {
    digest.update(Integer.toString(value.length).getBytes(StandardCharsets.UTF_8));
    digest.update((byte) ':');
    digest.update(value);
  }

  private static String digest(String value) {
    return digest(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String digest(byte[] value) {
    MessageDigest digest = sha256();
    return HexFormat.of().formatHex(digest.digest(value));
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("SHA-256 is required by every Java runtime", impossible);
    }
  }

  private static void copyTree(Path source, Path destination) throws IOException {
    Files.walkFileTree(
        source,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes)
              throws IOException {
            Files.createDirectories(destination.resolve(source.relativize(directory)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
              throws IOException {
            Path output = destination.resolve(source.relativize(file));
            try {
              Files.createLink(output, file);
            } catch (UnsupportedOperationException | IOException ignored) {
              Files.copy(file, output, StandardCopyOption.REPLACE_EXISTING);
            }
            return FileVisitResult.CONTINUE;
          }
        });
  }

  private static void deleteEmptyDirectories(Path root) throws IOException {
    if (!Files.isDirectory(root)) return;
    try (var paths = Files.walk(root)) {
      for (Path directory :
          paths.filter(Files::isDirectory).sorted(Comparator.reverseOrder()).toList()) {
        if (!directory.equals(root)) {
          try (var children = Files.list(directory)) {
            if (children.findAny().isEmpty()) Files.deleteIfExists(directory);
          }
        }
      }
    }
  }

  private static void deleteTree(Path root) throws IOException {
    if (!Files.exists(root)) return;
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

  private static void move(Path source, Path destination, boolean replace) throws IOException {
    List<StandardCopyOption> options = new ArrayList<>();
    options.add(StandardCopyOption.ATOMIC_MOVE);
    if (replace) options.add(StandardCopyOption.REPLACE_EXISTING);
    try {
      Files.move(source, destination, options.toArray(StandardCopyOption[]::new));
    } catch (AtomicMoveNotSupportedException ignored) {
      if (replace) Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
      else Files.move(source, destination);
    }
  }

  private void pruneGenerations(String retained) throws IOException {
    if (!Files.isDirectory(generations)) return;
    try (var paths = Files.list(generations)) {
      for (Path generation : paths.filter(Files::isDirectory).toList()) {
        if (!generation.getFileName().toString().equals(retained)) deleteTree(generation);
      }
    }
  }

  private static void writeAtomic(Path output, List<String> lines) throws IOException {
    Path temporary =
        output.resolveSibling(output.getFileName() + ".tmp-" + ProcessHandle.current().pid());
    String text = lines.isEmpty() ? "" : String.join("\n", lines) + "\n";
    Files.writeString(temporary, text, StandardCharsets.UTF_8);
    move(temporary, output, true);
  }

  private static int compareUtf8(String left, String right) {
    return java.util.Arrays.compareUnsigned(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private record ShardMetadata(String source, String target, String diskDigest) {}
}
