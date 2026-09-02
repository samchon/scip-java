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
          void work() { { int value = 1; } { int value = 1; } }
        }
        """;
    String json = compile(root, source);
    assertEquals(2, count(json, "\"name\":\"value\""));
    assertEquals(2, symbolsFor(json, "value").stream().distinct().count());
  }

  @Test
  void identicalSiblingIdentityIgnoresUnrelatedStatements(@TempDir Path root) {
    String original =
        """
        package example;
        public class Example {
          void work() { { int value = 1; } { int value = 1; } }
        }
        """;
    String changed =
        """
        package example;
        public class Example {
          void work() { int unrelated = 0; { int value = 1; } { int value = 1; } }
        }
        """;

    assertEquals(
        symbolsFor(compile(root.resolve("first"), original), "value"),
        symbolsFor(compile(root.resolve("second"), changed), "value"));
  }

  @Test
  void identicalLocalTypesGiveTheirMembersDistinctOwnerIdentities(@TempDir Path root) {
    String source =
        """
        package example;
        public class Example {
          void work() {
            { class Local { void run() {} } new Local().run(); }
            { class Local { void run() {} } new Local().run(); }
          }
        }
        """;

    String json = compile(root, source);
    assertEquals(2, symbolsFor(json, "Local").stream().distinct().count());
    assertEquals(2, edgeTargetsFor(json, "run()").stream().distinct().count());
  }

  @Test
  void appendingAnIdenticalSiblingKeepsTheExistingIdentity(@TempDir Path root) {
    String single =
        """
        package example;
        public class Example {
          void work() { { int value = 1; } }
        }
        """;
    String appended =
        """
        package example;
        public class Example {
          void work() { { int value = 1; } { int value = 1; } }
        }
        """;

    String original = symbolFor(compile(root.resolve("single"), single), "value");
    List<String> siblings = symbolsFor(compile(root.resolve("appended"), appended), "value");
    assertEquals(original, siblings.get(0));
    assertEquals(2, siblings.stream().distinct().count());
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

  @Test
  void graphUniverseNormalizesOnlyTheProducerScratchPath(@TempDir Path root) throws IOException {
    Path scratch = root.resolve("Tool Root");
    Files.createDirectories(scratch);
    Path firstPlugin = scratch.resolve("first.jar");
    Path secondPlugin = root.resolve("Other Root/second.jar");
    Files.createDirectories(secondPlugin.getParent());
    Files.writeString(firstPlugin, "same bytes");
    Files.writeString(secondPlugin, "same bytes");
    String expectedDigest = "58100dc8fc06562ce3e578231dc948e083520ee49c4b4ee5a5a28bb4b4003feb";
    assertEquals(expectedDigest, ScipOptionBuilder.graphPluginDigest(firstPlugin));
    assertEquals(expectedDigest, ScipOptionBuilder.graphPluginDigest(secondPlugin));
    Files.writeString(secondPlugin, "different bytes");
    assertFalse(expectedDigest.equals(ScipOptionBuilder.graphPluginDigest(secondPlugin)));

    String actualPath = scratch.resolve("plugin.jar").toString();
    String movedPath = root.resolve("Another Tool Root/plugin.jar").toString();
    assertEquals(
        ScipOptionBuilder.graphUniverseArgument("-cp=" + actualPath, scratch),
        ScipOptionBuilder.graphUniverseArgument(
            "-cp=" + movedPath, root.resolve("Another Tool Root")));
    assertFalse(
        ScipOptionBuilder.graphUniverseArgument("-Avalue=a\\b", scratch)
            .equals(ScipOptionBuilder.graphUniverseArgument("-Avalue=a/b", scratch)));
    assertFalse(
        ScipOptionBuilder.graphUniverseArgument(
                scratch + "-two" + java.io.File.separator + "plugin.jar", scratch)
            .equals(ScipOptionBuilder.graphUniverseArgument(actualPath, scratch)));
    String literalToken = "${SCIP_JAVA_TOOL}";
    String encodedLiteral =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(literalToken.getBytes(StandardCharsets.UTF_8));
    assertEquals(
        "v1|literal:" + encodedLiteral,
        ScipOptionBuilder.graphUniverseArgument(literalToken, scratch));
    String repeated =
        ScipOptionBuilder.graphUniverseArgument(actualPath + ",next=" + actualPath, scratch);
    assertEquals(2, count(repeated, "|tool"));
    assertEquals(
        ScipOptionBuilder.graphUniverseArgument("-Aİ=" + actualPath, scratch),
        ScipOptionBuilder.graphUniverseArgument(
            "-Aİ=" + movedPath, root.resolve("Another Tool Root")));
    if (java.io.File.separatorChar == '\\') {
      String portable = actualPath.replace('\\', '/');
      int separator = portable.indexOf('/', 3);
      String mixed =
          portable.substring(0, separator) + "\\" + portable.substring(separator + 1);
      assertEquals(
          ScipOptionBuilder.graphUniverseArgument(actualPath, scratch),
          ScipOptionBuilder.graphUniverseArgument(mixed, scratch));
      assertEquals(
          ScipOptionBuilder.graphUniverseArgument(actualPath, scratch),
          ScipOptionBuilder.graphUniverseArgument(
              actualPath.replace("Tool Root", "TOOL ROOT"), scratch));
      assertFalse(
          ScipOptionBuilder.graphUniverseArgument(
                  "-Avalue=" + scratch + "\\child,UPPER", scratch)
              .equals(
                  ScipOptionBuilder.graphUniverseArgument(
                      "-Avalue=" + scratch + "\\child,upper", scratch)));
      assertEquals(
          ScipOptionBuilder.graphUniverseArgument(
              "-cp=" + scratch + "\\Child Space\\plugin.jar", scratch),
          ScipOptionBuilder.graphUniverseArgument(
              "-cp="
                  + root.resolve("Another Tool Root")
                  + "/Child Space\\plugin.jar",
              root.resolve("Another Tool Root")));
    } else {
      assertFalse(
          ScipOptionBuilder.graphUniverseArgument(scratch + "\\child", scratch)
              .equals(ScipOptionBuilder.graphUniverseArgument(scratch + "/child", scratch)));
    }
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

  private static List<String> symbolsFor(String json, String name) {
    Pattern pattern =
        Pattern.compile(
            "\\{\\\"symbol\\\":\\\"([^\\\"]+)\\\",[^{}]*\\\"name\\\":\\\""
                + Pattern.quote(name)
                + "\\\"");
    Matcher matcher = pattern.matcher(json);
    List<String> result = new java.util.ArrayList<>();
    while (matcher.find()) result.add(matcher.group(1));
    return result;
  }

  private static List<String> edgeTargetsFor(String json, String targetName) {
    Pattern pattern =
        Pattern.compile(
            "\\\"to\\\":\\\"([^\\\"]+)\\\"[^{}]*\\\"targetName\\\":\\\""
                + Pattern.quote(targetName)
                + "\\\"");
    Matcher matcher = pattern.matcher(json);
    List<String> result = new java.util.ArrayList<>();
    while (matcher.find()) result.add(matcher.group(1));
    return result;
  }

  private static int count(String text, String needle) {
    int count = 0;
    for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
      count++;
    }
    return count;
  }
}
