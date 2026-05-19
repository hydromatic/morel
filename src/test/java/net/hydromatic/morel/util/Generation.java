/*
 * Licensed to Julian Hyde under one or more contributor license
 * agreements.  See the NOTICE file distributed with this work
 * for additional information regarding copyright ownership.
 * Julian Hyde licenses this file to you under the Apache
 * License, Version 2.0 (the "License"); you may not use this
 * file except in compliance with the License.  You may obtain a
 * copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND,
 * either express or implied.  See the License for the specific
 * language governing permissions and limitations under the
 * License.
 */
package net.hydromatic.morel.util;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.repeat;
import static com.google.common.collect.ImmutableList.sortedCopyOf;
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static org.junit.jupiter.api.Assertions.fail;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import net.hydromatic.morel.SignatureChecker;
import net.hydromatic.morel.TestUtils;
import net.hydromatic.morel.eval.Prop;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Generates code from metadata. */
public class Generation {
  private Generation() {}

  /**
   * Loads the data model of all structures, types, exceptions, and functions
   * from the {@code lib/*.sig} files. The result is immutable; callers should
   * hold the model and pass it to the methods that consume it.
   */
  public static Model loadModel() throws IOException {
    final File libDir = new File("lib");
    checkArgument(libDir.isDirectory(), "lib directory not found: %s", libDir);
    final SignatureChecker checker = new SignatureChecker();
    final Map<String, StrDef> structures = new TreeMap<>();
    final File @Nullable [] files =
        libDir.listFiles((d, n) -> n.endsWith(".sig"));
    requireNonNull(files, "no .sig files under lib/");
    for (File f : files) {
      final String structure =
          SignatureChecker.structureFromSigFileName(f.getName());
      if (structure == null) {
        continue;
      }
      final SignatureChecker.ParseResult parsed = checker.parseSpecsAndMeta(f);
      final Map<String, String> meta =
          parsed.structureMeta.getOrDefault(structure, Collections.emptyMap());
      final List<FnDef> fns = new ArrayList<>();
      final List<TyDef> tys = new ArrayList<>();
      final List<ExnDef> exns = new ArrayList<>();
      final List<SignatureChecker.SpecInfo> infos =
          parsed.specs.getOrDefault(structure, Collections.emptyList());
      for (SignatureChecker.SpecInfo info : infos) {
        switch (info.kind) {
          case FUNCTION:
            fns.add(
                new FnDef(
                    structure,
                    info.name,
                    info.displayType,
                    info.prototype == null ? "" : info.prototype,
                    info.description == null ? "" : info.description,
                    info.extra,
                    info.implemented,
                    info.specified,
                    info.method));
            break;
          case TYPE:
            tys.add(
                new TyDef(
                    structure,
                    info.name,
                    info.typeDecl,
                    info.description == null ? "" : info.description,
                    info.implemented));
            break;
          case EXCEPTION:
            exns.add(
                new ExnDef(
                    structure,
                    info.name,
                    info.exceptionType,
                    info.description == null ? "" : info.description,
                    info.implemented));
            break;
          default:
            break;
        }
      }
      structures.put(
          structure,
          new StrDef(
              structure,
              meta.getOrDefault("description", ""),
              meta.getOrDefault("overview", ""),
              meta.getOrDefault("specified", "basis"),
              fns,
              tys,
              exns));
    }
    return new ModelImpl(structures);
  }

  /**
   * Handle to the snapshot of all structures (each with its functions, types,
   * and exceptions) loaded from {@code lib/*.sig}. Callers obtain a Model via
   * {@link #loadModel()} and use the probe / iteration methods on it; the
   * underlying data is not part of the public API.
   */
  public interface Model {
    /** Names of all structures, in sorted order. */
    Set<String> structureNames();

    /**
     * True if structure {@code s} has a {@code val} spec named {@code name}.
     */
    boolean containsFunction(String s, String name);

    /**
     * True if structure {@code s} has a {@code val} spec named {@code name}
     * annotated with {@code [@@method]}.
     */
    boolean containsMethod(String s, String name);

    /**
     * True if structure {@code s} has a {@code type}, {@code eqtype}, or {@code
     * datatype} spec named {@code name}.
     */
    boolean containsType(String s, String name);
  }

  /**
   * Backing data for {@link Model}; field access is via the {@code impl(model)}
   * downcast within Generation.
   */
  private static class ModelImpl implements Model {
    final Map<String, StrDef> structures;

    ModelImpl(Map<String, StrDef> structures) {
      this.structures = structures;
    }

    @Override
    public Set<String> structureNames() {
      return structures.keySet();
    }

    @Override
    public boolean containsFunction(String s, String name) {
      final StrDef str = structures.get(s);
      return str != null
          && str.functions.stream()
              .anyMatch(fn -> fn.canonicalName().equals(name));
    }

    @Override
    public boolean containsMethod(String s, String name) {
      final StrDef str = structures.get(s);
      return str != null
          && str.functions.stream()
              .anyMatch(fn -> fn.method && fn.canonicalName().equals(name));
    }

    @Override
    public boolean containsType(String s, String name) {
      final StrDef str = structures.get(s);
      return str != null
          && str.types.stream().anyMatch(ty -> ty.name.equals(name));
    }
  }

  /** Returns the {@link ModelImpl} fields backing {@code model}. */
  private static ModelImpl impl(Model model) {
    return (ModelImpl) model;
  }

  /**
   * Returns the postfix form of a function call prototype.
   *
   * <p>Given a prototype like {@code "length l"}, returns {@code "l.length
   * ()"}. Given {@code "drop (l, i)"}, returns {@code "l.drop i"}. Given {@code
   * "substring (s, i, j)"}, returns {@code "s.substring (i, j)"}. Given the
   * curried {@code "iterate initialList listUpdate"}, returns {@code
   * "initialList.iterate listUpdate"}.
   *
   * <p>Rules (where {@code self} is the first argument):
   *
   * <ul>
   *   <li>Prefix unary {@code "name self"}: {@code self.name ()}
   *   <li>Prefix curried {@code "name self arg"}: {@code self.name arg}
   *   <li>Prefix 2-tuple {@code "name (self, arg)"}: {@code self.name arg}
   *   <li>Prefix 3+-tuple {@code "name (self, a, ...)"}: {@code self.name (a,
   *       ...)}
   * </ul>
   */
  static String postfixForm(String prototype) {
    final int space = prototype.indexOf(' ');
    if (space < 0) {
      // No arguments: shouldn't happen for documented functions, but handle
      return prototype + " ()";
    }
    final String name = prototype.substring(0, space);
    final String args = prototype.substring(space + 1).trim();
    if (!args.startsWith("(")) {
      // No parens: bare identifier (unary) or space-separated curried args.
      final int argSpace = args.indexOf(' ');
      if (argSpace < 0) {
        // Unary: self.name ()
        return args + "." + name + " ()";
      } else {
        // Curried: first token is self, remainder is the extra arg(s).
        return args.substring(0, argSpace)
            + "."
            + name
            + " "
            + args.substring(argSpace + 1);
      }
    }
    // Tuple: parse top-level comma-separated elements inside outer parens
    final List<String> parts =
        splitTopLevel(args.substring(1, args.length() - 1));
    if (parts.size() == 1) {
      return parts.get(0).trim() + "." + name + " ()";
    } else if (parts.size() == 2) {
      return parts.get(0).trim() + "." + name + " " + parts.get(1).trim();
    } else {
      // 3 or more: self.name (rest...)
      final String remaining =
          parts.subList(1, parts.size()).stream()
              .map(String::trim)
              .collect(Collectors.joining(", "));
      return parts.get(0).trim() + "." + name + " (" + remaining + ")";
    }
  }

  /** Splits a string at top-level commas (ignoring commas inside parens). */
  private static List<String> splitTopLevel(String s) {
    final List<String> parts = new ArrayList<>();
    int depth = 0;
    int start = 0;
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      if (c == '(') {
        depth++;
      } else if (c == ')') {
        depth--;
      } else if (c == ',' && depth == 0) {
        parts.add(s.substring(start, i));
        start = i + 1;
      }
    }
    parts.add(s.substring(start));
    return parts;
  }

  /**
   * Generates a two-column index table of structures into {@code reference.md}.
   *
   * <p>Each row links the structure name to its {@code docs/lib/{name}.md}
   * page; the description column contains the one-sentence description followed
   * by a comma-separated list of hyperlinked members (types, exceptions,
   * functions).
   */
  public static void generateStructureIndex(Model model, PrintWriter pw) {
    generateStructureIndexImpl(model, pw, "lib/", true);
  }

  /**
   * Generates a two-column index table of structures into {@code
   * docs/lib/index.md}.
   *
   * <p>Like {@link #generateStructureIndex} but uses local links (no {@code
   * lib/} prefix) since the file is already inside {@code docs/lib/}.
   */
  public static void generateLibIndex(Model model, PrintWriter pw) {
    generateStructureIndexImpl(model, pw, "", false);
  }

  private static void generateStructureIndexImpl(
      Model model, PrintWriter pw, String linkPrefix, boolean checkSort) {
    final ModelImpl m = impl(model);
    // Structures are sorted alphabetically by TreeMap.
    if (checkSort) {
      final List<String> structureNames =
          new ArrayList<>(m.structures.keySet());
      if (!Ordering.natural().isOrdered(structureNames)) {
        fail(
            "Structure names are not sorted\n"
                + TestUtils.diffLines(
                    structureNames, sortedCopyOf(structureNames)));
      }
    }
    pw.printf("%n");
    final Tabulator tabulator = new Tabulator(pw, -1, -1);
    tabulator.header("Structure", "Description");
    for (StrDef strDef : m.structures.values()) {
      final String kebab = toKebab(strDef.name);
      final String link =
          "[" + strDef.name + "](" + linkPrefix + kebab + ".md)";
      final String linkBase = linkPrefix + kebab + ".md";
      final LinkedHashSet<String> members = new LinkedHashSet<>();
      for (TyDef ty : strDef.types) {
        members.add(ty.name);
      }
      for (ExnDef e : strDef.exceptions) {
        members.add(e.name);
      }
      for (FnDef fn : strDef.functions) {
        members.add(fn.canonicalName());
      }
      final String memberList =
          members.stream()
              .map(mn -> "[`" + mn + "`](" + linkBase + "#" + mn + "-impl)")
              .collect(Collectors.joining(", "));
      tabulator.row(link, munge(strDef.description) + "<br>" + memberList);
    }
    pw.printf("%n");
  }

  /**
   * Converts a structure name in PascalCase to kebab-case.
   *
   * <p>Examples: {@code "IEEEReal"} &rarr; {@code "ieee-real"}, {@code
   * "ListPair"} &rarr; {@code "list-pair"}, {@code "StringCvt"} &rarr; {@code
   * "string-cvt"}.
   */
  public static String toKebab(String name) {
    return name.replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2")
        .replaceAll("([a-z])([A-Z])", "$1-$2")
        .toLowerCase();
  }

  /**
   * Inverse of {@link #toKebab}. Splits at hyphens and title-cases each
   * segment, with the single exception of {@code "ieee"}, which uppercases to
   * {@code "IEEE"}.
   *
   * <p>Examples: {@code "ieee-real"} &rarr; {@code "IEEEReal"}, {@code
   * "list-pair"} &rarr; {@code "ListPair"}, {@code "string-cvt"} &rarr; {@code
   * "StringCvt"}.
   */
  public static String fromKebab(String kebab) {
    final StringBuilder sb = new StringBuilder();
    for (String segment : kebab.split("-")) {
      if (segment.isEmpty()) {
        continue;
      }
      if (segment.equals("ieee")) {
        sb.append("IEEE");
      } else {
        sb.append(Character.toUpperCase(segment.charAt(0)));
        sb.append(segment, 1, segment.length());
      }
    }
    return sb.toString();
  }

  /** Generates the body of a per-structure docs page. */
  public static void generateStructureDoc(
      Model model, String structure, PrintWriter pw) {
    new StructureDocGenerator(model, structure, pw).generate();
  }

  /** Generates a table of properties into {@code reference.md}. */
  public static void generatePropertyTable(PrintWriter pw) {
    new PropertyTableGenerator(pw).generate();
  }

  /**
   * Dispatches to the appropriate generator based on a start-marker key.
   *
   * <p>Called by the unified {@code testGeneratedSections} lint test when it
   * encounters a {@code [//]: # (start:KEY)} marker in any {@code .md} file
   * under {@code docs/}. Supported keys:
   *
   * <ul>
   *   <li>{@code "structures"} &rarr; {@link #generateStructureIndex}
   *   <li>{@code "properties"} &rarr; {@link #generatePropertyTable}
   *   <li>{@code "lib/index"} &rarr; {@link #generateLibIndex}
   *   <li>{@code "lib/{name}"} &rarr; {@link #generateStructureDoc} for the
   *       structure whose kebab name is {@code name}
   * </ul>
   *
   * <p>Unrecognized keys are silently ignored (no output written).
   */
  public static void generateSection(Model model, String key, PrintWriter pw) {
    switch (key) {
      case "structures":
        generateStructureIndex(model, pw);
        break;
      case "properties":
        generatePropertyTable(pw);
        break;
      case "lib/index":
        generateLibIndex(model, pw);
        break;
      default:
        if (key.startsWith("lib/")) {
          final String kebab = key.substring(4);
          final String structureName =
              model.structureNames().stream()
                  .filter(n -> toKebab(n).equals(kebab))
                  .findFirst()
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "No structure for key: " + key));
          generateStructureDoc(model, structureName, pw);
        }
        break;
    }
  }

  private static String munge(String s) {
    return s.trim()
        .replace("α", "&alpha;")
        .replace("β", "&beta;")
        .replace("γ", "&gamma;")
        .replace("→", "&rarr;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("≤", "&le;")
        .replace("≥", "&ge;")
        .replace("`?&lt;&gt;`", "`?<>`")
        .replace("`&lt;`", "`<`")
        .replace("`&lt;&gt;`", "`<>`")
        .replace("`a &lt; b`", "`a < b`")
        .replace("`not (a &gt;= b)`", "`not (a >= b)`")
        .replace("&lt;br&gt;", "<br>")
        .replace("&lt;p&gt;", "<br><br>")
        .replace("&lt;sup&gt;", "<sup>")
        .replace("&lt;/sup&gt;", "</sup>")
        .replace("&lt;pre&gt;", "<pre>")
        .replace("&lt;/pre&gt;", "</pre>")
        .replace("|", "\\|")
        .replace("\n", " ")
        .replaceAll(" *<br>", "<br>");
  }

  /** Function definition. */
  private static class FnDef {
    final String structure;
    final String name;
    final String type;
    final String prototype;
    final String description;
    final @Nullable String extra;
    final boolean implemented;
    final String specified;
    final boolean method;

    FnDef(
        String structure,
        String name,
        String type,
        String prototype,
        String description,
        String extra,
        boolean implemented,
        String specified,
        boolean method) {
      this.structure = requireNonNull(structure, "structure");
      this.name = requireNonNull(name, "name");
      requireNonNull(type, "type");
      checkArgument(
          !type.isEmpty(), "type is empty for %s.%s", structure, name);
      this.type = type;
      this.prototype = requireNonNull(prototype, "prototype");
      this.description = requireNonNull(description, "description");
      this.extra = extra;
      this.implemented = implemented;
      this.specified = requireNonNull(specified, "specified");
      this.method = method;
    }

    String qualifiedName() {
      return structure + '.' + name;
    }

    /** Returns the name, stripping any disambiguation qualifier. */
    String canonicalName() {
      int comma = name.indexOf(", ");
      return comma >= 0 ? name.substring(0, comma) : name;
    }
  }

  /** Structure definition. */
  private static class StrDef {
    final String name;
    final String description;
    final String overview;
    final String specified;
    final ImmutableList<FnDef> functions;
    final ImmutableList<TyDef> types;
    final ImmutableList<ExnDef> exceptions;

    StrDef(
        String name,
        String description,
        String overview,
        String specified,
        Iterable<FnDef> functions,
        Iterable<TyDef> types,
        Iterable<ExnDef> exceptions) {
      this.name = requireNonNull(name, "name");
      this.description = requireNonNull(description, "description");
      this.overview = requireNonNull(overview, "overview");
      this.specified = requireNonNull(specified, "specified");
      this.functions = ImmutableList.copyOf(functions);
      this.types = ImmutableList.copyOf(types);
      this.exceptions = ImmutableList.copyOf(exceptions);
    }
  }

  /** Type definition. */
  private static class TyDef {
    final String structure;
    final String name;
    final String type;
    final String description;
    final boolean implemented;

    TyDef(
        String structure,
        String name,
        String type,
        String description,
        boolean implemented) {
      this.structure = requireNonNull(structure, "structure");
      this.name = requireNonNull(name, "name");
      this.type = requireNonNull(type, "type");
      this.description = requireNonNull(description, "description");
      this.implemented = implemented;
    }

    String qualifiedName() {
      return structure + '.' + name;
    }
  }

  /** Exception definition. */
  private static class ExnDef {
    final String structure;
    final String name;
    final @Nullable String type;
    final String description;
    final boolean implemented;

    ExnDef(
        String structure,
        String name,
        @Nullable String type,
        String description,
        boolean implemented) {
      this.structure = requireNonNull(structure, "structure");
      this.name = requireNonNull(name, "name");
      this.type = type;
      this.description = requireNonNull(description, "description");
      this.implemented = implemented;
    }

    String qualifiedName() {
      return structure + '.' + name;
    }
  }

  /** Generates the body of a per-structure Markdown page. */
  private static class StructureDocGenerator {
    final String structure;
    final PrintWriter pw;
    final StrDef strDef;
    final List<TyDef> tyDefs;
    final List<ExnDef> exnDefs;
    final List<FnDef> basisFns;
    final List<FnDef> morelFns;
    final List<FnDef> allFns;
    final Map<FnDef, String> fnAnchors;

    StructureDocGenerator(Model model, String structure, PrintWriter pw) {
      this.structure = structure;
      this.pw = pw;
      this.strDef =
          requireNonNull(
              impl(model).structures.get(structure),
              "structure not found: " + structure);
      this.tyDefs = strDef.types;
      this.exnDefs = strDef.exceptions;
      // Within the structure's doc page, basis-specified functions are
      // listed first, then a "Morel extensions" section. Preserve physical
      // .sig file order within each group.
      this.basisFns = new ArrayList<>();
      this.morelFns = new ArrayList<>();
      for (FnDef fn : strDef.functions) {
        if ("morel".equals(fn.specified)) {
          morelFns.add(fn);
        } else {
          basisFns.add(fn);
        }
      }
      this.allFns = new ArrayList<>();
      allFns.addAll(basisFns);
      allFns.addAll(morelFns);
      this.fnAnchors = buildFnAnchors(allFns);
    }

    void generate() {
      pw.println(stripLeadingSpace(strDef.overview.trim()));
      pw.println();
      if ("basis".equals(strDef.specified)) {
        pw.format(
            Locale.ROOT,
            "*Specified by the [Standard ML Basis Library]"
                + "(https://smlfamily.github.io/Basis/%s.html).*%n",
            toKebab(strDef.name));
        pw.println();
      }
      pw.println("## Synopsis");
      pw.println();
      synopsis();
      pw.println();
      for (TyDef ty : tyDefs) {
        typeSection(ty);
      }
      for (ExnDef exn : exnDefs) {
        exnSection(exn);
      }
      for (FnDef fn : allFns) {
        fnSection(fn);
      }
    }

    void synopsis() {
      pw.println("<pre>");
      for (TyDef ty : tyDefs) {
        pw.println(synopsisTypeLine(ty));
      }
      if (!tyDefs.isEmpty() && (!exnDefs.isEmpty() || !allFns.isEmpty())) {
        pw.println();
      }
      for (ExnDef exn : exnDefs) {
        pw.println(synopsisExnLine(exn));
      }
      if (!exnDefs.isEmpty() && !allFns.isEmpty()) {
        pw.println();
      }
      if ("morel".equals(strDef.specified)) {
        for (FnDef fn : allFns) {
          pw.println(synopsisFnLine(fn));
        }
      } else {
        for (FnDef fn : basisFns) {
          pw.println(synopsisFnLine(fn));
        }
        if (!morelFns.isEmpty()) {
          pw.println("(* Morel extensions *)");
          for (FnDef fn : morelFns) {
            pw.println(synopsisFnLine(fn));
          }
        }
      }
      pw.println("</pre>");
    }

    String synopsisTypeLine(TyDef ty) {
      final String link =
          format(
              Locale.ROOT,
              "<a id='%s' href=\"#%s-impl\">%s</a>",
              ty.name,
              ty.name,
              ty.name);
      // Link the declared name (the part before '=') leaving the rest intact.
      final int eqIdx = ty.type.indexOf('=');
      final String def = eqIdx >= 0 ? ty.type.substring(0, eqIdx) : ty.type;
      final String rest = eqIdx >= 0 ? ty.type.substring(eqIdx) : "";
      final int nameIdx = def.lastIndexOf(ty.name);
      if (nameIdx < 0) {
        return ty.type;
      }
      final String line =
          def.substring(0, nameIdx)
              + link
              + def.substring(nameIdx + ty.name.length())
              + rest;
      if (ty.type.length() > 70
          && line.contains(" = ")
          && line.contains(" | ")) {
        final int sepIdx = line.indexOf(" = ");
        final String prefix = line.substring(0, sepIdx);
        final String constructors = line.substring(sepIdx + 3);
        return prefix
            + "\n" //
            + "  = "
            + String.join(
                "\n" //
                    + "  | ",
                constructors.split(" \\| "));
      }
      return line;
    }

    String synopsisExnLine(ExnDef exn) {
      final String s =
          format(
              Locale.ROOT,
              "exception <a id='%s' href=\"#%s-impl\">%s</a>",
              exn.name,
              exn.name,
              exn.name);
      return exn.type != null ? s + " of " + exn.type : s;
    }

    String synopsisFnLine(FnDef fn) {
      final String anchor = fnAnchors.get(fn);
      return format(
          Locale.ROOT,
          "val <a id='%s' href=\"#%s-impl\">%s</a> : %s",
          anchor,
          anchor,
          fn.canonicalName(),
          toSml(fn.type));
    }

    void typeSection(TyDef ty) {
      pw.format(Locale.ROOT, "<a id=\"%s-impl\"></a>%n", ty.name);

      // Parse "datatype 'a list = ..." into keyword "datatype", rest "'a list".
      final int space = ty.type.indexOf(' ');
      final int eq = ty.type.indexOf('=');
      final String keyword = ty.type.substring(0, space);
      final String rest =
          (eq < 0 ? ty.type.substring(space) : ty.type.substring(space, eq))
              .trim();
      pw.format(
          Locale.ROOT,
          "<h3><code><strong>%s</strong> %s</code></h3>%n",
          keyword,
          rest);
      pw.println();
      pw.println(processDesc(ty.description));
      pw.println();
    }

    void exnSection(ExnDef exn) {
      pw.format(Locale.ROOT, "<a id=\"%s-impl\"></a>%n", exn.name);
      pw.format(
          Locale.ROOT,
          "<h3><code><strong>exception</strong> %s</code></h3>%n",
          exn.name);
      pw.println();
      pw.println(processDesc(exn.description));
      pw.println();
    }

    void fnSection(FnDef fn) {
      pw.format(Locale.ROOT, "<a id=\"%s-impl\"></a>%n", fnAnchors.get(fn));
      pw.format(Locale.ROOT, "<h3><code>%s</code></h3>%n", fn.canonicalName());
      pw.println();
      final String postfix =
          fn.method ? " (or `" + postfixForm(fn.prototype) + "`)" : "";
      final String line;
      if (fn.extra != null) {
        line =
            format(
                Locale.ROOT,
                "`%s`%s %s %s",
                fn.prototype,
                postfix,
                processDesc(fn.description),
                fn.extra.trim());
      } else {
        line =
            format(
                Locale.ROOT,
                "`%s`%s %s",
                fn.prototype,
                postfix,
                processDesc(fn.description));
      }
      pw.println(line.replaceAll("\\s+$", ""));
      if (!fn.implemented) {
        pw.println();
        pw.println("*Not yet implemented.*");
      }
      pw.println();
    }

    static String processDesc(String desc) {
      return stripLeadingSpace(desc.trim())
          .replace(
              "\n" //
                  + "\n" //
                  + "<p>",
              "\n" //
                  + "\n")
          .replace(
              "<p>",
              "\n" //
                  + "\n")
          .replace(
              "\n" //
                  + "\n" //
                  + "\n",
              "\n" //
                  + "\n")
          // Strip trailing whitespace from every line (after <p> rewrites,
          // which can otherwise leave a stranded leading-space-only line).
          .replaceAll("(?m)[ \\t]+$", "");
    }

    /** Strips the cosmetic space left by ocamldoc-style {@code * } lines. */
    static String stripLeadingSpace(String s) {
      return s.replaceAll("(?m)^ ", "");
    }

    static String toSml(String type) {
      return type.replace("α", "'a")
          .replace("β", "'b")
          .replace("γ", "'c")
          .replace("→", "->")
          .replace("≤", "<=")
          .replace("≥", ">=");
    }

    static Map<FnDef, String> buildFnAnchors(List<FnDef> fns) {
      // First pass: collect anchor names claimed by operator functions.
      final Set<String> used = new HashSet<>();
      for (FnDef fn : fns) {
        final String op = operatorAnchor(fn.canonicalName());
        if (op != null) {
          used.add(op);
        }
      }
      // Second pass: assign anchors, using name-fn if name is already taken.
      final Map<FnDef, String> result = new LinkedHashMap<>();
      for (FnDef fn : fns) {
        final String name = fn.canonicalName();
        String anchor = operatorAnchor(name);
        if (anchor == null) {
          anchor = name;
          if (!used.add(anchor)) {
            anchor = name + "-fn";
            used.add(anchor);
          }
        }
        result.put(fn, anchor);
      }
      return result;
    }

    static @Nullable String operatorAnchor(String name) {
      switch (name) {
        case "@":
          return "at";
        case "::":
          return "cons";
        case ":=":
          return "assign";
        case "!":
          return "deref";
        default:
          return null;
      }
    }
  }

  /** Generates a table of {@link Prop} values. */
  private static class PropertyTableGenerator {
    private final PrintWriter pw;

    PropertyTableGenerator(PrintWriter pw) {
      this.pw = pw;
    }

    void generate() {
      pw.printf("%n");
      final List<Prop> propList =
          Ordering.from(Comparator.comparing((Prop p) -> p.name()))
              .sortedCopy(Arrays.asList(Prop.values()));
      final Tabulator tabulator = new Tabulator(pw, 20, 6, 7, -1);
      tabulator.header("Name", "Type", "Default", "Description");
      for (Prop p : propList) {
        tabulator.row(
            p.camelName, p.typeName(), p.defaultValue(), munge(p.description));
      }
      pw.printf("%n");
    }
  }

  /** Generates a Markdown table. */
  private static class Tabulator {
    private final PrintWriter pw;
    private final String format;
    private final int[] widths;

    private Tabulator(PrintWriter pw, int... widths) {
      this.pw = pw;
      this.widths = widths;

      final StringBuilder b = new StringBuilder("|");
      for (int width : widths) {
        b.append(" ")
            .append(width < 0 ? "%s" : "%-" + width + "s")
            .append(" |");
      }
      b.append("%n");
      this.format = b.toString();
    }

    void row(Object... values) {
      pw.printf(format, values);
    }

    void header(String... names) {
      row((Object[]) names);

      Object[] hyphens = new Object[names.length];
      for (int i = 0; i < names.length; i++) {
        String name = names[i];
        hyphens[i] = repeat("-", Math.max(widths[i], name.length()));
      }
      row(hyphens);
    }
  }
}

// End Generation.java
