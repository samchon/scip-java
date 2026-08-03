package org.scip_code.scip_java.javac;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * Deterministic per-source graph facts produced from the same attributed javac tree as SCIP.
 *
 * <p>The file is deliberately a small producer-owned schema. The workspace aggregator binds these
 * shards to a successful build universe and publishes the versioned graph transaction; a compiler
 * invocation never needs to know about a consumer's transport protocol.
 */
public final class JavaGraphShard {
  public static final int SCHEMA_VERSION = 1;
  public static final String GRAPH_ROOT = "META-INF/scip-graph";

  /** One-based source evidence. */
  public record Evidence(String file, int startLine, int startColumn, int endLine, int endColumn) {}

  /** A declaration keyed by its canonical Java semantic symbol. */
  public record Node(
      String symbol,
      String kind,
      String name,
      String qualifiedName,
      String file,
      boolean exported,
      List<String> modifiers,
      String signature,
      Evidence evidence) {}

  /** A resolved relationship. Endpoints are canonical symbols or the source-file coordinate. */
  public record Edge(
      String from,
      String to,
      String kind,
      String access,
      String provenance,
      String targetKind,
      String targetName,
      String targetQualifiedName,
      Evidence evidence) {}

  /** A relationship site javac could not settle exactly. */
  public record Unresolved(
      String family, String reason, Evidence evidence, List<String> candidates) {}

  public final String source;
  public final String checkerDigest;
  public final String diskDigest;
  public final String target;
  public final String compilerVersion;
  public final List<Node> nodes;
  public final List<Edge> edges;
  public final List<Unresolved> unresolved;

  public JavaGraphShard(
      String source,
      String checkerDigest,
      String diskDigest,
      String target,
      String compilerVersion,
      List<Node> nodes,
      List<Edge> edges,
      List<Unresolved> unresolved) {
    this.source = source;
    this.checkerDigest = checkerDigest;
    this.diskDigest = diskDigest;
    this.target = target;
    this.compilerVersion = compilerVersion;
    this.nodes = List.copyOf(nodes);
    this.edges = List.copyOf(edges);
    this.unresolved = List.copyOf(unresolved);
  }

  /** Canonical shard path parallel to the existing {@code META-INF/scip} layout. */
  public static Path outputPath(Path targetRoot, Path relativeSource) {
    return outputPathAtRoot(targetRoot.resolve("META-INF").resolve("scip-graph"), relativeSource);
  }

  /** Shard path inside a task-owned generation root. */
  public static Path outputPathAtRoot(Path graphRoot, Path relativeSource) {
    String filename = relativeSource.getFileName().toString() + ".graph.json";
    return graphRoot.resolve(relativeSource).resolveSibling(filename);
  }

  /** Write through a sibling and atomically replace the complete prior source shard. */
  public void write(Path output) throws IOException {
    Files.createDirectories(output.getParent());
    Path temporary =
        output.resolveSibling(
            output.getFileName().toString() + ".tmp-" + ProcessHandle.current().pid());
    Files.writeString(temporary, toJson(), StandardCharsets.UTF_8);
    try {
      Files.move(
          temporary,
          output,
          java.nio.file.StandardCopyOption.ATOMIC_MOVE,
          java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, output, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
  }

  /** SHA-256 of compiler-owned UTF-8 text. */
  public static String digest(String text) {
    return digest(text.getBytes(StandardCharsets.UTF_8));
  }

  /** SHA-256 of exact disk bytes. */
  public static String digest(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException impossible) {
      throw new AssertionError("SHA-256 is required by every Java runtime", impossible);
    }
  }

  /** Canonical JSON: fixed keys and UTF-8 byte ordering for every set-like collection. */
  public String toJson() {
    List<Node> orderedNodes = new ArrayList<>(nodes);
    orderedNodes.sort(
        Comparator.comparing(Node::symbol, JavaGraphShard::compareUtf8)
            .thenComparing(Node::kind, JavaGraphShard::compareUtf8));
    List<Edge> orderedEdges = new ArrayList<>(edges);
    orderedEdges.sort(
        Comparator.comparing(Edge::from, JavaGraphShard::compareUtf8)
            .thenComparing(Edge::to, JavaGraphShard::compareUtf8)
            .thenComparing(Edge::kind, JavaGraphShard::compareUtf8)
            .thenComparing(edge -> String.valueOf(edge.access()), JavaGraphShard::compareUtf8)
            .thenComparing(edge -> String.valueOf(edge.provenance()), JavaGraphShard::compareUtf8)
            .thenComparing(edge -> edge.evidence().file(), JavaGraphShard::compareUtf8)
            .thenComparingInt(edge -> edge.evidence().startLine())
            .thenComparingInt(edge -> edge.evidence().startColumn()));
    List<Unresolved> orderedUnresolved = new ArrayList<>(unresolved);
    orderedUnresolved.sort(
        Comparator.comparing(Unresolved::family, JavaGraphShard::compareUtf8)
            .thenComparing(site -> site.evidence().file(), JavaGraphShard::compareUtf8)
            .thenComparingInt(site -> site.evidence().startLine())
            .thenComparingInt(site -> site.evidence().startColumn()));

    StringBuilder out = new StringBuilder();
    out.append('{');
    field(out, "schemaVersion", SCHEMA_VERSION).append(',');
    field(out, "language", "java").append(',');
    field(out, "source", source).append(',');
    field(out, "checkerDigest", checkerDigest).append(',');
    field(out, "diskDigest", diskDigest).append(',');
    field(out, "target", target).append(',');
    field(out, "compilerVersion", compilerVersion).append(',');
    out.append("\"nodes\":[");
    for (int i = 0; i < orderedNodes.size(); i++) {
      if (i != 0) out.append(',');
      node(out, orderedNodes.get(i));
    }
    out.append("],\"edges\":[");
    for (int i = 0; i < orderedEdges.size(); i++) {
      if (i != 0) out.append(',');
      edge(out, orderedEdges.get(i));
    }
    out.append("],\"unresolved\":[");
    for (int i = 0; i < orderedUnresolved.size(); i++) {
      if (i != 0) out.append(',');
      unresolved(out, orderedUnresolved.get(i));
    }
    return out.append("]}\n").toString();
  }

  private static void node(StringBuilder out, Node node) {
    out.append('{');
    field(out, "symbol", node.symbol()).append(',');
    field(out, "kind", node.kind()).append(',');
    field(out, "name", node.name()).append(',');
    field(out, "qualifiedName", node.qualifiedName()).append(',');
    field(out, "file", node.file()).append(',');
    field(out, "exported", node.exported()).append(',');
    out.append("\"modifiers\":[");
    List<String> modifiers = new ArrayList<>(node.modifiers());
    modifiers.sort(JavaGraphShard::compareUtf8);
    for (int i = 0; i < modifiers.size(); i++) {
      if (i != 0) out.append(',');
      string(out, modifiers.get(i));
    }
    out.append("],");
    field(out, "signature", node.signature()).append(',');
    out.append("\"evidence\":");
    evidence(out, node.evidence());
    out.append('}');
  }

  private static void edge(StringBuilder out, Edge edge) {
    out.append('{');
    field(out, "from", edge.from()).append(',');
    field(out, "to", edge.to()).append(',');
    field(out, "kind", edge.kind()).append(',');
    if (edge.access() == null) out.append("\"access\":null,");
    else field(out, "access", edge.access()).append(',');
    if (edge.provenance() == null) out.append("\"provenance\":null,");
    else field(out, "provenance", edge.provenance()).append(',');
    if (edge.targetKind() == null) out.append("\"targetKind\":null,");
    else field(out, "targetKind", edge.targetKind()).append(',');
    if (edge.targetName() == null) out.append("\"targetName\":null,");
    else field(out, "targetName", edge.targetName()).append(',');
    if (edge.targetQualifiedName() == null) out.append("\"targetQualifiedName\":null,");
    else field(out, "targetQualifiedName", edge.targetQualifiedName()).append(',');
    out.append("\"evidence\":");
    evidence(out, edge.evidence());
    out.append('}');
  }

  private static void unresolved(StringBuilder out, Unresolved unresolved) {
    out.append('{');
    field(out, "family", unresolved.family()).append(',');
    field(out, "reason", unresolved.reason()).append(',');
    out.append("\"evidence\":");
    evidence(out, unresolved.evidence());
    out.append(",\"candidates\":[");
    List<String> candidates = new ArrayList<>(unresolved.candidates());
    candidates.sort(JavaGraphShard::compareUtf8);
    for (int i = 0; i < candidates.size(); i++) {
      if (i != 0) out.append(',');
      string(out, candidates.get(i));
    }
    out.append("]}");
  }

  private static void evidence(StringBuilder out, Evidence evidence) {
    out.append('{');
    field(out, "file", evidence.file()).append(',');
    field(out, "startLine", evidence.startLine()).append(',');
    field(out, "startColumn", evidence.startColumn()).append(',');
    field(out, "endLine", evidence.endLine()).append(',');
    field(out, "endColumn", evidence.endColumn());
    out.append('}');
  }

  private static StringBuilder field(StringBuilder out, String name, String value) {
    string(out, name).append(':');
    return string(out, value);
  }

  private static StringBuilder field(StringBuilder out, String name, int value) {
    string(out, name).append(':').append(value);
    return out;
  }

  private static StringBuilder field(StringBuilder out, String name, boolean value) {
    string(out, name).append(':').append(value);
    return out;
  }

  private static StringBuilder string(StringBuilder out, String value) {
    out.append('"');
    for (int index = 0; index < value.length(); index++) {
      char ch = value.charAt(index);
      switch (ch) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\f' -> out.append("\\f");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (ch < 0x20) out.append(String.format("\\u%04x", (int) ch));
          else out.append(ch);
        }
      }
    }
    return out.append('"');
  }

  private static int compareUtf8(String left, String right) {
    return Arrays.compareUnsigned(
        left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8));
  }

  private JavaGraphShard() {
    throw new AssertionError("not instantiable");
  }
}
