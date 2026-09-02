package org.scip_code.scip_java.javac;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

public class ScipOptionBuilder {
  private String previousArg = "";
  private final ArrayList<String> oldArgs = new ArrayList<>();
  private final ArrayList<String> result = new ArrayList<>();
  private boolean isClasspathUpdated = false;

  public static final String ERRORPATH = System.getProperty("scip.errorpath", "");
  private static final String PLUGINPATH = System.getProperty("scip.pluginpath", "");
  private static final String SOURCEROOT = System.getProperty("scip.sourceroot", "");
  private static final String TARGETROOT = System.getProperty("scip.targetroot", "");
  private static final String GRAPH_ROOT = System.getProperty("scip.graph.root", "");
  private static final String GRAPH_TARGET = System.getProperty("scip.graph.target", "");
  private static final boolean GRAPH_ENABLED =
      Boolean.parseBoolean(System.getProperty("scip.graph.enabled", "false"));
  private static final String OUTPUT = System.getProperty("scip.output", "");
  private static final String OLD_OUTPUT = System.getProperty("scip.old-output", "");

  public void processArgument(String arg) {
    oldArgs.add(arg);
    arg = unwrapQuote(arg);
    if ("-processorpath".equals(previousArg)
        || "-classpath".equals(previousArg)
        || "-cp".equals(previousArg)) {
      isClasspathUpdated = true;
      result.add(javacPath(PLUGINPATH) + File.pathSeparator + javacPath(arg));
    } else if (arg.startsWith("-J")) {
      // Ignore Java launcher arguments.
    } else if (arg.startsWith("-Xplugin:ErrorProne")) {
      // Disable ErrorProne since it's not necessary.
    } else if (arg.startsWith("-Xlint")) {
      // Disable linting options since they may fail the build.
    } else {
      result.add(arg);
    }

    previousArg = arg;
  }

  private String unwrapQuote(String arg) {
    if (arg.startsWith("\"") && arg.endsWith("\"")) {
      return arg.substring(1, arg.length() - 1);
    } else {
      return arg;
    }
  }

  private String wrapQuote(String arg) {
    if (arg.startsWith("\"") && arg.endsWith("\"")) {
      return arg;
    } else {
      return "\"" + arg + "\"";
    }
  }

  private String xpluginOption() {
    StringBuilder option =
        new StringBuilder(
            String.format(
                "-Xplugin:scip -sourceroot-base64:%s -targetroot-base64:%s",
                encodedPath(SOURCEROOT), encodedPath(TARGETROOT)));
    if (GRAPH_ENABLED) {
      option.append(" -graph:on");
      option.append(" -graph-root-base64:").append(encodedPath(GRAPH_ROOT));
      option.append(" -graph-target-base64:").append(encodedValue(graphTarget()));
    }
    return "\"" + option + "\"";
  }

  private static String javacPath(String value) {
    return File.separatorChar == '\\' ? value.replace('\\', '/') : value;
  }

  private static String encodedPath(String value) {
    return encodedValue(value);
  }

  private static String encodedValue(String value) {
    return Base64.getUrlEncoder()
        .withoutPadding()
        .encodeToString(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String graphTarget() {
    if (!"maven".equals(GRAPH_TARGET)) return GRAPH_TARGET;
    try {
      Path sourceRoot = Paths.get(SOURCEROOT).toAbsolutePath().normalize();
      Path workingDirectory =
          Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
      if (!workingDirectory.startsWith(sourceRoot)) return GRAPH_TARGET;
      Path relative = sourceRoot.relativize(workingDirectory);
      String module = relative.toString().replace(File.separatorChar, '/');
      return GRAPH_TARGET + ":" + (module.isEmpty() ? "." : module);
    } catch (RuntimeException ignored) {
      return GRAPH_TARGET;
    }
  }

  public ArrayList<String> finalResult() {
    if (!isClasspathUpdated) {
      result.add("-classpath");
      result.add(javacPath(PLUGINPATH));
    }
    result.add(xpluginOption());
    ArrayList<String> finalResult = new ArrayList<>();
    for (String arg : result) {
      finalResult.add(wrapQuote(arg));
    }
    return finalResult;
  }

  public void writeFile(String file, List<String> lines, Charset charset, OpenOption... options)
      throws IOException {
    Path path = Paths.get(file);
    Files.createDirectories(path.getParent());
    Files.write(path, lines, charset, options);
  }

  public void write() throws IOException {
    writeFile(OUTPUT, finalResult(), Charset.defaultCharset());
    writeFile(
        OLD_OUTPUT,
        oldArgs,
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    if (GRAPH_ENABLED) writeGraphUniverse();
  }

  private void writeGraphUniverse() throws IOException {
    String target = graphTarget();
    String targetKey = JavaGraphShard.digest(target);
    Path root = Paths.get(GRAPH_ROOT).resolve(".universe");
    Path seen = root.resolve(".seen").resolve(targetKey);
    Path output = root.resolve(targetKey + ".args");
    Files.createDirectories(seen.getParent());
    boolean firstInvocation;
    try {
      Files.createFile(seen);
      firstInvocation = true;
    } catch (FileAlreadyExistsException ignored) {
      firstInvocation = false;
    }
    List<String> invocation = new ArrayList<>();
    invocation.add("@invocation");
    Path plugin = Paths.get(PLUGINPATH).toAbsolutePath().normalize();
    invocation.add("@plugin");
    invocation.add(encodedValue(graphPluginDigest(plugin)));
    for (String argument : oldArgs) {
      invocation.add(encodedValue(graphUniverseArgument(argument, plugin.getParent())));
    }
    if (firstInvocation) {
      Files.write(
          output,
          invocation,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.TRUNCATE_EXISTING);
    } else {
      Files.write(
          output,
          invocation,
          StandardCharsets.UTF_8,
          StandardOpenOption.CREATE,
          StandardOpenOption.APPEND);
    }
  }

  static String graphPluginDigest(Path plugin) throws IOException {
    return JavaGraphShard.digest(Files.readAllBytes(plugin));
  }

  static String graphUniverseArgument(String argument, Path scratch) {
    String scratchPath = scratch.toAbsolutePath().normalize().toString();
    String comparison = comparablePath(argument);
    String needle = comparablePath(scratchPath);
    StringBuilder identity = new StringBuilder("v1");
    int cursor = 0;
    while (cursor < argument.length()) {
      int match = comparison.indexOf(needle, cursor);
      while (match >= 0
          && (!pathBoundary(argument, match - 1, true)
              || !pathBoundary(argument, match + scratchPath.length(), false))) {
        match = comparison.indexOf(needle, match + 1);
      }
      if (match < 0) {
        literal(identity, argument.substring(cursor));
        break;
      }
      literal(identity, argument.substring(cursor, match));
      identity.append("|tool");
      cursor = match + scratchPath.length();
    }
    if (argument.isEmpty()) literal(identity, "");
    return identity.toString();
  }

  private static String comparablePath(String value) {
    if (File.separatorChar != '\\') return value;
    StringBuilder comparable = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      if (character == '\\') character = '/';
      if (character >= 'A' && character <= 'Z') character += 'a' - 'A';
      comparable.append(character);
    }
    return comparable.toString();
  }

  private static boolean pathBoundary(String value, int index, boolean before) {
    if (index < 0 || index >= value.length()) return true;
    char character = value.charAt(index);
    return Character.isWhitespace(character)
        || "=;:\"'".indexOf(character) >= 0
        || (!before
            && (character == '/' || (File.separatorChar == '\\' && character == '\\')));
  }

  private static void literal(StringBuilder identity, String value) {
    identity.append("|literal:").append(encodedValue(value));
  }
}
