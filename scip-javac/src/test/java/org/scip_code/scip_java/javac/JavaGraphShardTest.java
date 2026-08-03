package org.scip_code.scip_java.javac;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaGraphShardTest {
  private static final String SOURCE_PATH = "example/Example.java";

  @Test
  void emitsTypedFactsFromTheAttributedCompilation(@TempDir Path root) {
    String source =
        """
        package example;
        import java.util.List;
        @interface Mark {}
        interface Base { void run(); }
        interface Derived extends Base {}
        final class Item {}
        class Hidden { public void leaked() {} }
        @Mark public class Example implements Base {
          Example() { this(0); }
          Example(int initial) { super(); value = initial; }
          private int value;
          private List<String> names;
          @Override public void run() { value++; helper(); new Item(); }
          void accessModes() { value = 1; int read = value; value += read; }
          void anonymous() { new Base() { public void run() {} }; }
          Base base;
          void invoke() { base.run(); }
          void helper() {}
          @org.junit.Test void behavior() { helper(); }
          Class<?> reflected() throws Exception { return Class.forName("example.Item"); }
        }
        """;

    String json = compile(root, source);
    for (String family :
        List.of(
            "contains",
            "exports",
            "imports",
            "calls",
            "accesses",
            "instantiates",
            "type_ref",
            "implements",
            "overrides",
            "decorates",
            "tests")) {
      assertTrue(json.contains("\"kind\":\"" + family + "\""), family + " edge");
    }
    assertTrue(json.contains("\"access\":\"read\""));
    assertTrue(json.contains("\"access\":\"write\""));
    assertTrue(json.contains("\"access\":\"readwrite\""));
    assertTrue(count(json, "\"kind\":\"accesses\"") >= 4);
    assertTrue(count(json, "\"kind\":\"instantiates\"") >= 2);
    assertTrue(json.contains("\"name\":\"<anonymous>\""));
    assertTrue(json.contains("\"reason\":\"reflection\""));
    assertTrue(json.contains("\"family\":\"dispatches\""));
    assertTrue(json.contains("\"reason\":\"dynamic\""));
    assertTrue(json.contains("\"provenance\":\"junit4\""));
    assertFalse(json.contains("\"kind\":\"dispatches\""));
    assertTrue(
        json.contains(
            "\"name\":\"leaked()\",\"qualifiedName\":\"example.Hidden.leaked()\",\"file\":\"example/Example.java\",\"exported\":false"));
    assertTrue(json.contains("\"checkerDigest\":"));
    assertTrue(json.endsWith("}\n"));
  }

  @Test
  void overloadInsertionAndReorderingKeepCanonicalIdentity(@TempDir Path root) {
    String original =
        """
        package example;
        public class Example {
          void target(String value) {}
          void target(int value) {}
        }
        """;
    String reordered =
        """
        package example;
        public class Example {
          void target(boolean value) {}
          void target(int value) {}
          void target(String value) {}
        }
        """;

    String first = compile(root.resolve("first"), original);
    String second = compile(root.resolve("second"), reordered);
    assertEquals(
        symbolFor(first, "target(java.lang.String)"),
        symbolFor(second, "target(java.lang.String)"));
    assertEquals(symbolFor(first, "target(int)"), symbolFor(second, "target(int)"));
    assertFalse(symbolFor(second, "target(boolean)").isEmpty());
  }

  @Test
  void constructorInsertionAndReorderingKeepCanonicalIdentity(@TempDir Path root) {
    String original =
        """
        package example;
        public class Example {
          Example() {}
          Example(String value) {}
        }
        """;
    String reordered =
        """
        package example;
        public class Example {
          Example(int value) {}
          Example(String value) {}
          Example() {}
        }
        """;

    String first = compile(root.resolve("first"), original);
    String second = compile(root.resolve("second"), reordered);
    assertEquals(symbolFor(first, "Example()"), symbolFor(second, "Example()"));
    assertEquals(
        symbolFor(first, "Example(java.lang.String)"),
        symbolFor(second, "Example(java.lang.String)"));
    assertFalse(symbolFor(second, "Example(int)").isEmpty());
  }

  @Test
  void recordsSealedTypesGenericsAndLambdasRetainAttributedDeclarations(@TempDir Path root) {
    String source =
        """
        package example;
        import java.util.function.Function;
        public class Example {
          sealed interface Shape permits Circle {}
          static final class Circle implements Shape {}
          record Pair<T>(T left, T right) {}
          Function<String, Integer> length = value -> value.length();
        }
        """;

    String json = compile(root, source);
    assertTrue(json.contains("\"name\":\"Shape\""));
    assertTrue(json.contains("\"name\":\"Circle\""));
    assertTrue(json.contains("\"name\":\"Pair\""));
    assertTrue(json.contains("\"name\":\"left\""));
    assertTrue(json.contains("\"name\":\"right\""));
    assertTrue(json.contains("\"name\":\"value\""));
    assertTrue(json.contains("\"kind\":\"implements\""));
  }

  @Test
  void moduleAndPackageDescriptorsEmitDeclarations(@TempDir Path root) throws IOException {
    Path sourceRoot = root.resolve("source").toAbsolutePath();
    Path targetRoot = root.resolve("target").toAbsolutePath();
    TestCompiler compiler = graphCompiler(sourceRoot, targetRoot);
    CompileResult result =
        compiler.compile(
            List.of(
                new VirtualFile("module-info.java", "module sample.module { exports example; }\n"),
                new VirtualFile("example/package-info.java", "@Deprecated package example;\n"),
                new VirtualFile(SOURCE_PATH, "package example; public class Example {}\n")),
            Collections.emptyList());
    assertTrue(result.isSuccess, () -> "compilation failed:\n" + result.stdout);

    String module =
        Files.readString(targetRoot.resolve("META-INF/scip-graph/module-info.java.graph.json"));
    String packageInfo =
        Files.readString(
            targetRoot.resolve("META-INF/scip-graph/example/package-info.java.graph.json"));
    assertTrue(module.contains("\"kind\":\"module\""));
    assertTrue(module.contains("\"name\":\"sample.module\""));
    assertTrue(packageInfo.contains("\"kind\":\"package\""));
    assertTrue(packageInfo.contains("\"name\":\"example\""));
  }

  @Test
  void identicalCompilationsWriteByteIdenticalShards(@TempDir Path root) {
    String source =
        """
        package example;
        public class Example {
          private int value;
          int read() { return value; }
        }
        """;
    assertEquals(compile(root.resolve("one"), source), compile(root.resolve("two"), source));
  }

  @Test
  void unrelatedStatementsKeepLocalAndAnonymousTypeIdentity(@TempDir Path root) {
    String original =
        """
        package example;
        public class Example {
          void work() {
            class Local {}
            Runnable value = new Runnable() { public void run() {} };
          }
        }
        """;
    String changed =
        """
        package example;
        public class Example {
          void work() {
            int unrelated = 1;
            class Local {}
            Runnable value = new Runnable() { public void run() {} };
          }
        }
        """;

    String first = compile(root.resolve("first"), original);
    String second = compile(root.resolve("second"), changed);
    assertEquals(symbolFor(first, "Local"), symbolFor(second, "Local"));
    assertEquals(symbolFor(first, "<anonymous>"), symbolFor(second, "<anonymous>"));
  }

  @Test
  void anonymousBodyEditsKeepTheAnonymousTypeIdentity(@TempDir Path root) {
    String original =
        """
        package example;
        public class Example {
          Runnable value = new Runnable() { public void run() {} };
        }
        """;
    String changed =
        """
        package example;
        public class Example {
          Runnable value = new Runnable() { public void run() { int body = 1; } };
        }
        """;

    String first = compile(root.resolve("first"), original);
    String second = compile(root.resolve("second"), changed);
    assertEquals(symbolFor(first, "<anonymous>"), symbolFor(second, "<anonymous>"));
  }

  @Test
  void structurallyDistinctSiblingLocalsDoNotCollide(@TempDir Path root) {
    String source =
        """
        package example;
        public class Example {
          void work() { { int value = 1; } { int value = 2; } }
        }
        """;
    String json = compile(root, source);
    assertEquals(2, count(json, "\"name\":\"value\""));
  }

  @Test
  void graphOptOutDoesNotTouchAnExistingGraphShard(@TempDir Path root) throws IOException {
    Path sourceRoot = root.resolve("source").toAbsolutePath();
    Path targetRoot = root.resolve("target").toAbsolutePath();
    Path shard =
        targetRoot
            .resolve("META-INF")
            .resolve("scip-graph")
            .resolve("example")
            .resolve("Example.java.graph.json");
    Files.createDirectories(shard.getParent());
    Files.writeString(shard, "last-successful-generation\n");
    TestCompiler compiler =
        new TestCompiler(
            TestCompiler.PROCESSOR_PATH,
            List.of("-Xplugin:scip -targetroot:" + targetRoot + " -sourceroot:" + sourceRoot),
            targetRoot,
            sourceRoot);

    CompileResult result =
        compiler.compile(
            List.of(new VirtualFile(SOURCE_PATH, "package example; class Example {}\n")),
            Collections.emptyList());

    assertTrue(result.isSuccess, () -> "compilation failed:\n" + result.stdout);
    assertEquals("last-successful-generation\n", Files.readString(shard));
  }

  @Test
  void graphTargetEncodingPreservesSpacesAndUnicode(@TempDir Path root) {
    String target = ":module with spaces 한글:compileJava";
    String encoded =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(target.getBytes(StandardCharsets.UTF_8));
    ScipJavacOptions options =
        ScipJavacOptions.parse(
            new String[] {
              "-targetroot:" + root.resolve("target"),
              "-sourceroot:" + root.resolve("source"),
              "-graph:on",
              "-graph-target-base64:" + encoded
            },
            null);

    assertEquals(target, options.graphTarget);
    assertTrue(options.errors.isEmpty(), options.errors::toString);
  }

  private static String compile(Path root, String source) {
    Path sourceRoot = root.resolve("source").toAbsolutePath();
    Path targetRoot = root.resolve("target").toAbsolutePath();
    TestCompiler compiler = graphCompiler(sourceRoot, targetRoot);
    CompileResult result =
        compiler.compile(
            List.of(
                new VirtualFile(SOURCE_PATH, source),
                new VirtualFile(
                    "org/junit/Test.java", "package org.junit; public @interface Test {}\n")),
            Collections.emptyList());
    assertTrue(result.isSuccess, () -> "compilation failed:\n" + result.stdout);
    Path shard =
        targetRoot
            .resolve("META-INF")
            .resolve("scip-graph")
            .resolve("example")
            .resolve("Example.java.graph.json");
    assertTrue(Files.isRegularFile(shard), () -> "expected graph shard at " + shard);
    try {
      return Files.readString(shard);
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    }
  }

  private static TestCompiler graphCompiler(Path sourceRoot, Path targetRoot) {
    return new TestCompiler(
        TestCompiler.PROCESSOR_PATH,
        List.of(
            "-Xplugin:scip -targetroot:"
                + targetRoot
                + " -sourceroot:"
                + sourceRoot
                + " -graph:on -graph-target:main"),
        targetRoot,
        sourceRoot);
  }

  private static String symbolFor(String json, String name) {
    Pattern pattern =
        Pattern.compile(
            "\\{\\\"symbol\\\":\\\"([^\\\"]+)\\\",[^{}]*\\\"name\\\":\\\""
                + Pattern.quote(name)
                + "\\\"");
    Matcher matcher = pattern.matcher(json);
    assertTrue(matcher.find(), () -> "missing node " + name + " in:\n" + json);
    return matcher.group(1);
  }

  private static int count(String text, String needle) {
    int count = 0;
    for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
      count++;
    }
    return count;
  }
}
