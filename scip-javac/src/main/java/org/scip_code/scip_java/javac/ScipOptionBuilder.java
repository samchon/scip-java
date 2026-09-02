package org.scip_code.scip_java.javac;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class ScipOptionBuilder {
  private static final ConcurrentHashMap<Path, Object> GRAPH_INVOCATION_LOCKS =
      new ConcurrentHashMap<>();
  private String previousArg = "";
  private final ArrayList<String> oldArgs = new ArrayList<>();
  private final ArrayList<String> result = new ArrayList<>();
  private boolean isClasspathUpdated = false;

  public static final String ERRORPATH = setting("scip.errorpath", "SCIP_ERRORPATH");
  private static final String PLUGINPATH = setting("scip.pluginpath", "SCIP_PLUGINPATH");
  private static final String SOURCEROOT = setting("scip.sourceroot", "SCIP_SOURCEROOT");
  private static final String TARGETROOT = setting("scip.targetroot", "SCIP_TARGETROOT");
  private static final String GRAPH_ROOT = setting("scip.graph.root", "SCIP_GRAPH_ROOT");
  private static final String GRAPH_TARGET = setting("scip.graph.target", "SCIP_GRAPH_TARGET");
  private static final boolean GRAPH_ENABLED =
      Boolean.parseBoolean(setting("scip.graph.enabled", "SCIP_GRAPH_ENABLED"));
  private static final String OUTPUT = setting("scip.output", "SCIP_OUTPUT");
  private static final String OLD_OUTPUT = setting("scip.old-output", "SCIP_OLD_JAVAC_OPTS");

  private static String setting(String property, String environment) {
    String value = System.getProperty(property);
    return value != null ? value : System.getenv().getOrDefault(environment, "");
  }

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

  private static String unwrapQuote(String arg) {
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
    List<String> invocation = new ArrayList<>();
    invocation.add("@invocation");
    Path plugin = Paths.get(PLUGINPATH).toAbsolutePath().normalize();
    invocation.add("@plugin");
    invocation.add(encodedValue(graphPluginDigest(plugin)));
    for (String argument : oldArgs) {
      invocation.add(encodedValue(graphUniverseArgument(argument, plugin.getParent())));
    }
    writeGraphInvocation(
        root, targetKey, graphInvocationSlot(oldArgs, plugin.getParent()), invocation);
  }

  static String graphInvocationSlot(List<String> arguments, Path scratch) {
    for (int index = 0; index < arguments.size(); index++) {
      String argument = unwrapQuote(arguments.get(index));
      if ("-d".equals(argument) && index + 1 < arguments.size()) {
        return JavaGraphShard.digest(
            graphUniverseArgument(unwrapQuote(arguments.get(index + 1)), scratch));
      }
      if (argument.startsWith("-d=") && argument.length() > 3) {
        return JavaGraphShard.digest(graphUniverseArgument(argument.substring(3), scratch));
      }
    }
    return JavaGraphShard.digest("default-output");
  }

  static void writeGraphInvocation(
      Path root, String targetKey, String slotKey, List<String> invocation) throws IOException {
    byte[] content = (String.join("\n", invocation) + "\n").getBytes(StandardCharsets.UTF_8);
    Path directory = root.resolve(targetKey + ".args.d");
    Path output = directory.resolve(slotKey + ".args");
    Files.createDirectories(directory);
    Object processLock = GRAPH_INVOCATION_LOCKS.computeIfAbsent(output, ignored -> new Object());
    synchronized (processLock) {
      Path locks = root.resolve(".locks");
      Files.createDirectories(locks);
      Path lockFile = locks.resolve(targetKey + "-" + slotKey + ".lock");
      try (FileChannel channel =
              FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
          var ignored = channel.lock()) {
        writeGraphInvocation(output, content);
      }
    }
  }

  private static void writeGraphInvocation(Path output, byte[] content) throws IOException {
    Path directory = output.getParent();
    String slotKey = output.getFileName().toString().replaceFirst("\\.args$", "");
    Path temporary =
        directory.resolve(
            slotKey
                + ".tmp-"
                + ProcessHandle.current().pid()
                + "-"
                + Thread.currentThread().getId());
    Files.write(
        temporary, content, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    try {
      try {
        Files.move(
            temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(temporary, output, StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(temporary);
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
      if (File.separatorChar == '\\'
          && cursor < argument.length()
          && (argument.charAt(cursor) == '/' || argument.charAt(cursor) == '\\')) {
        identity.append("|separator");
        cursor++;
      }
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
        || (!before && (character == '/' || (File.separatorChar == '\\' && character == '\\')));
  }

  private static void literal(StringBuilder identity, String value) {
    identity.append("|literal:").append(encodedValue(value));
  }
}
