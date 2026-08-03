package org.scip_code.scip_java.javac;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Tree;
import com.sun.source.tree.VariableTree;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.NestingKind;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import org.scip_code.scip_java.shared.ScipRange;

/** Collects one source shard directly from attributed javac elements and trees. */
final class JavaGraphDocumentBuilder {
  private static final Set<ElementKind> LOCAL_KINDS =
      Set.of(
          ElementKind.PARAMETER,
          ElementKind.EXCEPTION_PARAMETER,
          ElementKind.LOCAL_VARIABLE,
          ElementKind.RESOURCE_VARIABLE,
          ElementKind.BINDING_VARIABLE);

  private final CompilationUnitTree compilationUnit;
  private final Types types;
  private final Trees trees;
  private final Elements elements;
  private final String sourceFile;
  private final String sourceText;
  private final String target;
  private final Map<Element, String> symbols = new IdentityHashMap<>();
  private final Map<String, JavaGraphShard.Node> nodes = new LinkedHashMap<>();
  private final Map<String, JavaGraphShard.Edge> edges = new LinkedHashMap<>();
  private final Map<String, JavaGraphShard.Unresolved> unresolved = new LinkedHashMap<>();

  JavaGraphDocumentBuilder(
      CompilationUnitTree compilationUnit,
      Types types,
      Trees trees,
      Elements elements,
      String sourceFile,
      String sourceText,
      String target) {
    this.compilationUnit = compilationUnit;
    this.types = types;
    this.trees = trees;
    this.elements = elements;
    this.sourceFile = sourceFile;
    this.sourceText = sourceText;
    this.target = target;
  }

  void declare(Element element, Tree tree, ScipRange range, String signature) {
    String kind = graphKind(element);
    if (kind == null || range == null) return;
    String symbol = symbol(element);
    String name = displayName(element);
    String qualifiedName = qualifiedName(element, name);
    JavaGraphShard.Node node =
        new JavaGraphShard.Node(
            symbol,
            kind,
            name,
            qualifiedName,
            sourceFile,
            isExported(element),
            modifiers(element),
            signature,
            evidence(range));
    JavaGraphShard.Node previous = nodes.putIfAbsent(symbol, node);
    if (previous != null && !previous.equals(node)) {
      throw new IllegalStateException("javac graph symbol changed within one source: " + symbol);
    }

    Element owner = declarationOwner(element.getEnclosingElement());
    edge(
        owner == null ? sourceFile : symbol(owner),
        symbol,
        "contains",
        null,
        null,
        element,
        evidence(range));
    if (node.exported()) {
      edge(sourceFile, symbol, "exports", null, null, element, evidence(range));
    }
  }

  void reference(
      Element targetElement,
      TreePath site,
      Tree siteTree,
      ScipRange range,
      String family,
      String access) {
    if (targetElement == null) {
      unresolved(family, siteTree, "analysis-error", List.of());
      return;
    }
    if (range == null) return;
    String from = ownerSymbol(site);
    String to = symbol(targetElement);
    edge(from, to, family, access, null, targetElement, evidence(range));
    if ("calls".equals(family)) {
      String framework = testFramework(site);
      if (framework != null) {
        edge(from, to, "tests", null, framework, targetElement, evidence(range));
      }
      if (mayDispatch(targetElement, siteTree)) {
        unresolved("dispatches", siteTree, "dynamic", List.of(to));
      }
    }
    if ("calls".equals(family) && isReflection(targetElement)) {
      unresolved("calls", siteTree, "reflection", List.of(to));
    }
  }

  void unresolved(String family, Tree tree, String reason, List<String> candidates) {
    JavaGraphShard.Evidence evidence = evidence(tree);
    if (evidence == null) return;
    JavaGraphShard.Unresolved site =
        new JavaGraphShard.Unresolved(family, reason, evidence, List.copyOf(candidates));
    String key =
        family
            + '\0'
            + evidence.file()
            + '\0'
            + evidence.startLine()
            + '\0'
            + evidence.startColumn()
            + '\0'
            + reason;
    unresolved.putIfAbsent(key, site);
  }

  void inheritance(Element child, Element parent, Tree tree, String family) {
    if (child == null || parent == null) {
      unresolved(family, tree, "analysis-error", List.of());
      return;
    }
    edge(symbol(child), symbol(parent), family, null, null, parent, evidence(tree));
  }

  void override(Element implementation, Element declaration, Tree tree) {
    if (implementation == null || declaration == null) return;
    JavaGraphShard.Evidence evidence = evidence(tree);
    edge(
        symbol(implementation),
        symbol(declaration),
        "overrides",
        null,
        null,
        declaration,
        evidence);
  }

  JavaGraphShard build(Path absoluteSource) {
    String diskDigest = "";
    try {
      if (Files.isRegularFile(absoluteSource)) {
        diskDigest = JavaGraphShard.digest(Files.readAllBytes(absoluteSource));
      }
    } catch (IOException ignored) {
      // The checker digest remains authoritative when a generated or virtual source has no disk
      // row.
    }
    return new JavaGraphShard(
        sourceFile,
        JavaGraphShard.digest(sourceText),
        diskDigest,
        target,
        System.getProperty("java.version", ""),
        new ArrayList<>(nodes.values()),
        new ArrayList<>(edges.values()),
        new ArrayList<>(unresolved.values()));
  }

  String symbol(Element element) {
    if (element == null) return sourceFile;
    String cached = symbols.get(element);
    if (cached != null) return cached;
    String value = canonicalSymbol(element);
    symbols.put(element, value);
    return value;
  }

  private String canonicalSymbol(Element element) {
    if (element instanceof ModuleElement module) {
      return encode("java-module", module.getQualifiedName().toString());
    }
    if (element instanceof PackageElement packageElement) {
      return encode(
          "java-package", moduleName(element), packageElement.getQualifiedName().toString());
    }
    if (element instanceof TypeElement type && isLocalType(type)) {
      TreePath declaration = trees.getPath(element);
      String scope = declaration == null ? sourceFile : structuralScope(declaration);
      return encode(
          "java-local-type",
          moduleName(element),
          sourceFile,
          element.getKind().name().toLowerCase(Locale.ROOT),
          localTypeSignature(type),
          scope);
    }
    TypeElement owner = binaryOwner(element);
    String module = moduleName(element);
    String binaryOwner = ownerIdentity(owner);
    String kind = element.getKind().name().toLowerCase(Locale.ROOT);
    String signature = structuralSignature(element);
    if (LOCAL_KINDS.contains(element.getKind()) || isAnonymous(element)) {
      TreePath declaration = trees.getPath(element);
      String scope = declaration == null ? sourceFile : structuralScope(declaration);
      return encode("java-local", module, binaryOwner, kind, signature, scope);
    }
    return encode("java", module, binaryOwner, kind, signature);
  }

  private String structuralSignature(Element element) {
    if (element instanceof ModuleElement module) return module.getQualifiedName().toString();
    if (element instanceof PackageElement packageElement) {
      return packageElement.getQualifiedName().toString();
    }
    if (element instanceof TypeElement type) {
      return elements.getBinaryName(type).toString();
    }
    if (element instanceof ExecutableElement executable) {
      String parameters =
          executable.getParameters().stream()
              .map(parameter -> canonicalType(parameter.asType()))
              .collect(Collectors.joining(","));
      String returnType =
          element.getKind() == ElementKind.CONSTRUCTOR
              ? "<init>"
              : canonicalType(executable.getReturnType());
      return element.getSimpleName()
          + "<"
          + executable.getTypeParameters().size()
          + ">("
          + parameters
          + ")->"
          + returnType;
    }
    if (element instanceof VariableElement variable) {
      return element.getSimpleName() + ":" + canonicalType(variable.asType());
    }
    return element.getSimpleName() + ":" + canonicalType(element.asType());
  }

  private String ownerIdentity(TypeElement owner) {
    if (owner == null) return "";
    return isLocalType(owner) ? symbol(owner) : elements.getBinaryName(owner).toString();
  }

  private String localTypeSignature(TypeElement type) {
    String parents =
        types.directSupertypes(type.asType()).stream()
            .map(this::canonicalType)
            .sorted(JavaGraphDocumentBuilder::compareUtf8)
            .collect(Collectors.joining(","));
    String name = type.getSimpleName().toString();
    return (name.isEmpty() ? "<anonymous>" : name) + "(" + parents + ")";
  }

  private String canonicalType(TypeMirror type) {
    try {
      return types.erasure(type).toString();
    } catch (RuntimeException ignored) {
      return type == null ? "<missing>" : type.toString();
    }
  }

  /**
   * A source-structural local scope: named declarations and semantic control-tree text, never a
   * declaration ordinal or coordinate. Inserting an overload or moving a declaration leaves it
   * stable.
   */
  private String structuralScope(TreePath declaration) {
    List<String> parts = new ArrayList<>();
    for (TreePath path = declaration; path != null; path = path.getParentPath()) {
      Tree leaf = path.getLeaf();
      if (leaf instanceof ClassTree type) {
        parts.add("class:" + type.getSimpleName());
      } else if (leaf instanceof MethodTree method) {
        Element element = trees.getElement(path);
        parts.add("method:" + (element == null ? method.getName() : structuralSignature(element)));
      } else if (leaf instanceof VariableTree variable) {
        parts.add("variable:" + normalizedVariable(variable));
      } else {
        switch (leaf.getKind()) {
          case IF,
              FOR_LOOP,
              ENHANCED_FOR_LOOP,
              WHILE_LOOP,
              DO_WHILE_LOOP,
              SWITCH,
              SWITCH_EXPRESSION,
              TRY,
              CATCH,
              LAMBDA_EXPRESSION,
              NEW_CLASS,
              CONDITIONAL_EXPRESSION ->
              parts.add(
                  leaf.getKind().name()
                      + ":"
                      + (leaf instanceof com.sun.source.tree.NewClassTree creation
                          ? normalizedNewClass(creation)
                          : normalizedTree(leaf)));
          default -> {
            // Blocks and wrapper expressions carry no positional discriminator.
          }
        }
      }
    }
    return JavaGraphShard.digest(String.join("/", parts));
  }

  private static String normalizedTree(Tree tree) {
    return tree.toString().replaceAll("\\s+", " ").trim();
  }

  private static String normalizedVariable(VariableTree variable) {
    Tree initializer = variable.getInitializer();
    String value =
        initializer instanceof com.sun.source.tree.NewClassTree creation
            ? normalizedNewClass(creation)
            : initializer == null ? "" : normalizedTree(initializer);
    return variable.getName() + ":" + variable.getType() + "=" + value;
  }

  private static String normalizedNewClass(com.sun.source.tree.NewClassTree creation) {
    String enclosing =
        creation.getEnclosingExpression() == null
            ? ""
            : normalizedTree(creation.getEnclosingExpression()) + ".";
    String arguments =
        creation.getArguments().stream()
            .map(JavaGraphDocumentBuilder::normalizedTree)
            .collect(Collectors.joining(","));
    return enclosing + "new " + creation.getIdentifier() + "(" + arguments + ")";
  }

  private String ownerSymbol(TreePath site) {
    for (TreePath path = site.getParentPath(); path != null; path = path.getParentPath()) {
      Tree leaf = path.getLeaf();
      if (!(leaf instanceof ClassTree
          || leaf instanceof MethodTree
          || leaf instanceof VariableTree)) continue;
      Element element = trees.getElement(path);
      if (element != null && graphKind(element) != null) return symbol(element);
    }
    return sourceFile;
  }

  private String testFramework(TreePath site) {
    for (TreePath path = site.getParentPath(); path != null; path = path.getParentPath()) {
      Element element = trees.getElement(path);
      if (element instanceof ExecutableElement executable) {
        String framework = testFramework(executable);
        if (framework != null) return framework;
      }
      if (element instanceof TypeElement type) {
        String framework = testFramework(type);
        if (framework != null) return framework;
      }
    }
    return null;
  }

  private static String testFramework(Element element) {
    for (AnnotationMirror annotation : element.getAnnotationMirrors()) {
      String name = annotation.getAnnotationType().toString();
      if (name.equals("org.junit.Test")) return "junit4";
      if (name.equals("org.junit.jupiter.api.Test")) return "junit5";
      if (name.equals("org.testng.annotations.Test")) return "testng";
    }
    return null;
  }

  private static boolean mayDispatch(Element element, Tree tree) {
    if (!(element instanceof ExecutableElement executable)
        || element.getKind() != ElementKind.METHOD) return false;
    Set<Modifier> modifiers = executable.getModifiers();
    if (modifiers.contains(Modifier.STATIC)
        || modifiers.contains(Modifier.PRIVATE)
        || modifiers.contains(Modifier.FINAL)) return false;
    if (element.getEnclosingElement() instanceof TypeElement owner
        && owner.getModifiers().contains(Modifier.FINAL)) return false;
    if (tree instanceof MethodInvocationTree invocation
        && invocation.getMethodSelect().toString().startsWith("super.")) return false;
    return true;
  }

  private static boolean isReflection(Element element) {
    Element owner = element.getEnclosingElement();
    String qualified = owner instanceof TypeElement type ? type.getQualifiedName().toString() : "";
    String name = element.getSimpleName().toString();
    return (qualified.equals("java.lang.Class") && name.equals("forName"))
        || (qualified.equals("java.lang.reflect.Method") && name.equals("invoke"))
        || (qualified.equals("java.lang.reflect.Constructor") && name.equals("newInstance"))
        || (qualified.equals("java.lang.reflect.Field")
            && (name.equals("get") || name.equals("set")));
  }

  private void edge(
      String from,
      String to,
      String kind,
      String access,
      String provenance,
      Element targetElement,
      JavaGraphShard.Evidence evidence) {
    if (from == null || from.isEmpty() || to == null || to.isEmpty() || evidence == null) return;
    String key =
        kind
            + '\0'
            + from
            + '\0'
            + to
            + '\0'
            + String.valueOf(access)
            + '\0'
            + String.valueOf(provenance)
            + '\0'
            + evidence.file()
            + '\0'
            + evidence.startLine()
            + '\0'
            + evidence.startColumn();
    edges.putIfAbsent(
        key,
        new JavaGraphShard.Edge(
            from,
            to,
            kind,
            access,
            provenance,
            targetElement == null ? null : graphKind(targetElement),
            targetElement == null ? null : displayName(targetElement),
            targetElement == null ? null : qualifiedName(targetElement, displayName(targetElement)),
            evidence));
  }

  private JavaGraphShard.Evidence evidence(ScipRange range) {
    return new JavaGraphShard.Evidence(
        sourceFile,
        range.startLine() + 1,
        range.startCharacter() + 1,
        range.endLine() + 1,
        range.endCharacter() + 1);
  }

  private JavaGraphShard.Evidence evidence(Tree tree) {
    if (tree == null) return null;
    SourcePositions positions = trees.getSourcePositions();
    long start = positions.getStartPosition(compilationUnit, tree);
    long end = positions.getEndPosition(compilationUnit, tree);
    if (start == Diagnostic.NOPOS || end == Diagnostic.NOPOS || end <= start) return null;
    var lines = compilationUnit.getLineMap();
    return new JavaGraphShard.Evidence(
        sourceFile,
        (int) lines.getLineNumber(start),
        (int) lines.getColumnNumber(start),
        (int) lines.getLineNumber(end),
        (int) lines.getColumnNumber(end));
  }

  private static Element declarationOwner(Element element) {
    for (Element current = element; current != null; current = current.getEnclosingElement()) {
      if (graphKind(current) != null) return current;
    }
    return null;
  }

  private static String graphKind(Element element) {
    return switch (element.getKind()) {
      case CLASS, RECORD -> "class";
      case INTERFACE, ANNOTATION_TYPE -> "interface";
      case ENUM -> "enum";
      case METHOD -> "method";
      case CONSTRUCTOR -> "constructor";
      case FIELD, ENUM_CONSTANT -> "field";
      case RECORD_COMPONENT -> "field";
      case PARAMETER -> "parameter";
      case LOCAL_VARIABLE, EXCEPTION_PARAMETER, RESOURCE_VARIABLE, BINDING_VARIABLE -> "variable";
      case TYPE_PARAMETER -> "type";
      case PACKAGE -> "package";
      case MODULE -> "module";
      default -> null;
    };
  }

  private static boolean isAnonymous(Element element) {
    return element instanceof TypeElement && element.getSimpleName().length() == 0;
  }

  private static boolean isLocalType(TypeElement element) {
    return element.getNestingKind() == NestingKind.LOCAL
        || element.getNestingKind() == NestingKind.ANONYMOUS;
  }

  private TypeElement binaryOwner(Element element) {
    for (Element current = element; current != null; current = current.getEnclosingElement()) {
      if (current instanceof TypeElement type) return type;
    }
    return null;
  }

  private String moduleName(Element element) {
    try {
      ModuleElement module = elements.getModuleOf(element);
      return module == null || module.isUnnamed()
          ? "<unnamed>"
          : module.getQualifiedName().toString();
    } catch (RuntimeException ignored) {
      return "<unnamed>";
    }
  }

  private String displayName(Element element) {
    if (element instanceof ModuleElement module) return module.getQualifiedName().toString();
    if (element instanceof PackageElement packageElement) {
      return packageElement.getQualifiedName().toString();
    }
    if (element instanceof ExecutableElement executable) {
      String base =
          element.getKind() == ElementKind.CONSTRUCTOR
              ? element.getEnclosingElement().getSimpleName().toString()
              : element.getSimpleName().toString();
      return base
          + executable.getParameters().stream()
              .map(parameter -> canonicalType(parameter.asType()))
              .collect(Collectors.joining(", ", "(", ")"));
    }
    String name = element.getSimpleName().toString();
    return name.isEmpty() ? "<anonymous>" : name;
  }

  private String qualifiedName(Element element, String name) {
    if (element instanceof ModuleElement module) return module.getQualifiedName().toString();
    if (element instanceof PackageElement packageElement) {
      return packageElement.getQualifiedName().toString();
    }
    Element owner = declarationOwner(element.getEnclosingElement());
    if (owner == null) {
      if (element instanceof TypeElement type) return type.getQualifiedName().toString();
      return name;
    }
    String ownerName =
        owner instanceof TypeElement type
            ? type.getQualifiedName().toString()
            : qualifiedName(owner, displayName(owner));
    return ownerName.isEmpty() ? name : ownerName + "." + name;
  }

  private boolean isExported(Element element) {
    Set<Modifier> modifiers = element.getModifiers();
    if (!modifiers.contains(Modifier.PUBLIC) && !modifiers.contains(Modifier.PROTECTED))
      return false;
    for (Element owner = element.getEnclosingElement();
        owner != null;
        owner = owner.getEnclosingElement()) {
      if (owner instanceof TypeElement) {
        Set<Modifier> ownerModifiers = owner.getModifiers();
        if (!ownerModifiers.contains(Modifier.PUBLIC)
            && !ownerModifiers.contains(Modifier.PROTECTED)) return false;
      }
    }
    ModuleElement module = elements.getModuleOf(element);
    if (module == null || module.isUnnamed()) return true;
    PackageElement packageElement = elements.getPackageOf(element);
    for (ModuleElement.Directive directive : module.getDirectives()) {
      if (directive instanceof ModuleElement.ExportsDirective exports
          && exports.getPackage().equals(packageElement)) return true;
    }
    return false;
  }

  private static List<String> modifiers(Element element) {
    List<String> result = new ArrayList<>();
    for (Modifier modifier : element.getModifiers()) {
      switch (modifier) {
        case PUBLIC -> result.add("public");
        case PROTECTED -> result.add("protected");
        case PRIVATE -> result.add("private");
        case ABSTRACT -> result.add("abstract");
        case STATIC -> result.add("static");
        case FINAL -> result.add("readonly");
        default -> {
          // The common graph modifier vocabulary has no Java-specific spelling.
        }
      }
    }
    return result;
  }

  private static String encode(String... fields) {
    StringBuilder out = new StringBuilder();
    for (String field : fields) out.append(field.length()).append(':').append(field);
    return out.toString();
  }

  private static int compareUtf8(String left, String right) {
    return java.util.Arrays.compareUnsigned(
        left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
        right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}
