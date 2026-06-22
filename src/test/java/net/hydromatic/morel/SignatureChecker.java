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
package net.hydromatic.morel;

import static java.lang.String.format;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.Ml.ml;
import static net.hydromatic.morel.TestUtils.toPascalCase;
import static net.hydromatic.morel.TestUtils.toSnakeCase;
import static org.hamcrest.CoreMatchers.instanceOf;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import com.google.common.io.Files;
import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.MultiType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;
import net.hydromatic.morel.util.Generation;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Validates signature declarations against enum values in {@link BuiltIn}. */
public class SignatureChecker {
  private final TypeSystem typeSystem;

  public SignatureChecker() {
    typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
  }

  /** Parses a .sig file and verifies that it is a signature declaration. */
  void checkSignatureFile(File file) throws IOException {
    final String content =
        Files.asCharSource(file, StandardCharsets.UTF_8).read();
    ml(content)
        .withParser(
            parser -> {
              try {
                AstNode node = parser.statementSemicolonOrEof();
                assertThat(node, notNullValue());
                // Unwrap an optional AttributedDecl carrying
                // structure-level attributes.
                if (node instanceof Ast.AttributedDecl) {
                  node = ((Ast.AttributedDecl) node).decl;
                }
                assertThat(
                    "File: " + file.getName(),
                    node,
                    instanceOf(Ast.SignatureDecl.class));
                new SignatureChecker().checkSignature((Ast.SignatureDecl) node);
              } catch (Exception e) {
                throw new RuntimeException(
                    "Failed to parse " + file.getName(), e);
              }
            });
  }

  void checkSignature(Ast.SignatureDecl signatureDecl) {
    for (Ast.SignatureBind bind : signatureDecl.binds) {
      verifyBuiltInType(bind, structureName(bind));
    }
  }

  /**
   * Returns the canonical string form of a Morel type, parsed from {@code
   * typeString}. Unicode forms ({@code α}, {@code →}, etc.) are first rewritten
   * to ASCII. Returns {@code null} if the string cannot be parsed.
   */
  static @Nullable String canonicalizeType(String typeString) {
    String s = unicodeToAscii(typeString);
    // 'order' is a Morel keyword — escape so the parser sees an identifier.
    s = s.replaceAll("\\border\\b", "`order`");
    try {
      final MorelParserImpl parser = new MorelParserImpl(new StringReader(s));
      final Ast.Type t = parser.typeSafe();
      final AstTypeStringifier stringifier = new AstTypeStringifier();
      return stringifier.str(t);
    } catch (Exception e) {
      return null;
    }
  }

  private static String unicodeToAscii(String t) {
    return t.replace("α", "'a")
        .replace("β", "'b")
        .replace("γ", "'c")
        .replace("→", "->")
        .replace("≤", "<=")
        .replace("≥", ">=");
  }

  /**
   * Parses a {@code .sig} file and returns, for each structure declared in it,
   * the list of {@link SpecInfo} entries describing its functions, types, and
   * exceptions. Commented-out {@code (* val ... *)} blocks are also returned,
   * with {@link SpecInfo#implemented} set to {@code false}.
   */
  Map<String, List<SpecInfo>> parseSpecs(File file) throws IOException {
    return parseSpecsAndMeta(file).specs;
  }

  /** Result of parsing a .sig file. */
  public static class ParseResult {
    public final Map<String, List<SpecInfo>> specs;
    /** Per-structure floating-attribute payloads (name → payload). */
    public final Map<String, Map<String, String>> structureMeta;

    ParseResult(
        Map<String, List<SpecInfo>> specs,
        Map<String, Map<String, String>> structureMeta) {
      this.specs = specs;
      this.structureMeta = structureMeta;
    }
  }

  /**
   * Returns the canonical structure name for the given {@code .sig} filename,
   * e.g. {@code "list-pair.sig"} → {@code "ListPair"}. Delegates to {@link
   * Generation#fromKebab}.
   */
  public static @Nullable String structureFromSigFileName(String fileName) {
    if (!fileName.endsWith(".sig")) {
      return null;
    }
    final String stem = fileName.substring(0, fileName.length() - 4);
    final String name = Generation.fromKebab(stem);
    return name.isEmpty() ? null : name;
  }

  public ParseResult parseSpecsAndMeta(File file) throws IOException {
    final String content =
        Files.asCharSource(file, StandardCharsets.UTF_8).read();
    final Map<String, List<SpecInfo>> result = new LinkedHashMap<>();
    final Map<String, Map<String, String>> structureMeta = new HashMap<>();
    ml(content)
        .withParser(
            parser -> {
              try {
                final AstNode rawNode = parser.statementSemicolonOrEof();
                assertThat(rawNode, notNullValue());
                // A .sig file may wrap the `signature ... = sig ... end` in
                // an AttributedDecl carrying structure-level attributes
                // ([@@specified], [@@description], (** overview *)).
                final List<Ast.Attribute> declAttrs;
                final AstNode node;
                if (rawNode instanceof Ast.AttributedDecl) {
                  final Ast.AttributedDecl ad = (Ast.AttributedDecl) rawNode;
                  declAttrs = ad.attributes;
                  node = ad.decl;
                } else {
                  declAttrs = Collections.emptyList();
                  node = rawNode;
                }
                assertThat(
                    "File: " + file.getName(),
                    node,
                    instanceOf(Ast.SignatureDecl.class));
                final Ast.SignatureDecl decl = (Ast.SignatureDecl) node;
                for (Ast.SignatureBind bind : decl.binds) {
                  final String structure = structureName(bind);
                  // Structure-level metadata: prefer attributes on the
                  // signature decl ([@@specified], [@@description], and
                  // [@@doc] from the leading (** overview *) comment).
                  // Fall back to the [@@@...] floating-attribute form for
                  // backwards compatibility.
                  final Map<String, String> meta = new HashMap<>();
                  for (Ast.Spec spec : bind.specs) {
                    if (spec.op == Op.FLOATING_ATTR_SPEC) {
                      final Ast.FloatingAttrSpec f =
                          (Ast.FloatingAttrSpec) spec;
                      if (f.attribute.payload instanceof Ast.Literal) {
                        final Object v =
                            ((Ast.Literal) f.attribute.payload).value;
                        if (v instanceof String) {
                          meta.put(f.attribute.name, (String) v);
                        }
                      }
                    }
                  }
                  for (Ast.Attribute a : declAttrs) {
                    if (a.payload instanceof Ast.Literal) {
                      final Object v = ((Ast.Literal) a.payload).value;
                      if (v instanceof String) {
                        // `(** overview *)` is desugared to [@@doc "..."];
                        // store it under "overview" for the doc generator.
                        final String key =
                            "doc".equals(a.name) ? "overview" : a.name;
                        meta.put(key, (String) v);
                      }
                    }
                  }
                  structureMeta.put(structure, meta);
                  final String defaultSpecified =
                      meta.getOrDefault("specified", "basis");
                  result
                      .computeIfAbsent(structure, k -> new ArrayList<>())
                      .addAll(specsFromBind(structure, bind, defaultSpecified));
                }
              } catch (Exception e) {
                throw new RuntimeException(
                    "Failed to parse " + file.getName(), e);
              }
            });

    // Second pass: scan raw lines for commented-out specs like
    // "(* val foo : T *)" — the parser doesn't see these. We append them to
    // the matching structure's list with implemented=false.
    if (!result.isEmpty()) {
      // Single-structure files are the norm; pick the first structure as the
      // owner of any commented entries.
      final String structure = result.keySet().iterator().next();
      result.get(structure).addAll(commentedSpecs(content));
    }
    return new ParseResult(result, structureMeta);
  }

  private List<SpecInfo> specsFromBind(
      String structure, Ast.SignatureBind bind, String defaultSpecified) {
    requireNonNull(defaultSpecified, "defaultSpecified");
    final List<SpecInfo> specs = new ArrayList<>();
    for (Ast.Spec spec : bind.specs) {
      final List<Ast.Attribute> attrs;
      final Ast.Spec inner;
      if (spec.op == Op.ATTRIBUTED_SPEC) {
        final Ast.AttributedSpec a = (Ast.AttributedSpec) spec;
        attrs = a.attributes;
        inner = a.spec;
      } else if (spec.op == Op.FLOATING_ATTR_SPEC) {
        continue; // floating attrs handled separately
      } else {
        attrs = Collections.emptyList();
        inner = spec;
      }
      final boolean method = hasAttribute(attrs, "method");
      final String specified =
          requireNonNull(
              stringAttribute(attrs, "specified", defaultSpecified),
              "specified");
      final String prototype = stringAttribute(attrs, "prototype", null);
      final String syntax = stringAttribute(attrs, "syntax", null);
      final String extra = stringAttribute(attrs, "extra", null);
      final String description = stringAttribute(attrs, "doc", null);
      if (inner.op == Op.SPEC_VAL) {
        final Ast.ValSpec valSpec = (Ast.ValSpec) inner;
        final AstTypeStringifier stringifier = new AstTypeStringifier();
        specs.add(
            new SpecInfo(
                SpecKind.FUNCTION,
                valSpec.name.name,
                stringifier.str(valSpec.type),
                true,
                method,
                specified,
                prototype,
                syntax,
                extra,
                description,
                valSpec.type.toString(),
                "",
                null));
      } else if (inner.op == Op.SPEC_TYPE) {
        final Ast.TypeSpec typeSpec = (Ast.TypeSpec) inner;
        specs.add(
            new SpecInfo(
                SpecKind.TYPE,
                typeSpec.name.name,
                "",
                true,
                false,
                specified,
                null,
                null,
                null,
                description,
                "",
                typeSpec.toString(),
                null));
      } else if (inner.op == Op.SPEC_DATATYPE) {
        final Ast.DatatypeSpec datatypeSpec = (Ast.DatatypeSpec) inner;
        specs.add(
            new SpecInfo(
                SpecKind.TYPE,
                datatypeSpec.name.name,
                "",
                true,
                false,
                specified,
                null,
                null,
                null,
                description,
                "",
                datatypeSpec.toString(),
                null));
      } else if (inner.op == Op.SPEC_EXCEPTION) {
        final Ast.ExceptionSpec exnSpec = (Ast.ExceptionSpec) inner;
        specs.add(
            new SpecInfo(
                SpecKind.EXCEPTION,
                exnSpec.name.name,
                "",
                true,
                false,
                specified,
                null,
                null,
                null,
                description,
                "",
                "",
                exnSpec.type == null ? null : exnSpec.type.toString()));
      }
    }
    return specs;
  }

  private static boolean hasAttribute(List<Ast.Attribute> attrs, String name) {
    for (Ast.Attribute a : attrs) {
      if (a.name.equals(name)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Returns the string payload of the named attribute, or {@code defaultValue}
   * if not present.
   */
  private static @Nullable String stringAttribute(
      List<Ast.Attribute> attrs, String name, @Nullable String defaultValue) {
    for (Ast.Attribute a : attrs) {
      if (a.name.equals(name) && a.payload instanceof Ast.Literal) {
        final Object v = ((Ast.Literal) a.payload).value;
        if (v instanceof String) {
          return (String) v;
        }
      }
    }
    return defaultValue;
  }

  private static final Pattern COMMENTED_VAL_PATTERN =
      Pattern.compile(
          "^\\s*val\\s+(`[^`]+`|[A-Za-z_][\\w']*|[^\\s:]+)\\s*:\\s*(.+?)\\s*$",
          Pattern.DOTALL);

  /**
   * Extracts commented-out specs like {@code (* val foo : T *)} from the raw
   * file text. Returns a list of {@link SpecInfo} with {@code implemented =
   * false}.
   */
  private static List<SpecInfo> commentedSpecs(String content) {
    final List<SpecInfo> result = new ArrayList<>();
    final String[] lines = content.split("\n", -1);
    int i = 0;
    while (i < lines.length) {
      final String line = lines[i];
      // Looking for "(*" on its own line, followed by val/type/datatype line(s)
      // and "*)". We're conservative: only match the simple, well-formatted
      // shape produced by the convention.
      if (line.trim().equals("(*")) {
        final int start = i + 1;
        int end = start;
        while (end < lines.length && !lines[end].trim().equals("*)")) {
          end++;
        }
        if (end < lines.length) {
          final StringBuilder buf = new StringBuilder();
          for (int j = start; j < end; j++) {
            buf.append(lines[j]).append('\n');
          }
          parseCommentedSpec(buf.toString(), result);
          i = end + 1;
          continue;
        }
      }
      i++;
    }
    return result;
  }

  private static void parseCommentedSpec(String text, List<SpecInfo> result) {
    final String trimmed = text.trim();
    // Match "val name : type" possibly spanning lines.
    if (trimmed.startsWith("val ")) {
      final Matcher m = COMMENTED_VAL_PATTERN.matcher(trimmed);
      if (m.matches()) {
        String name = m.group(1);
        if (name.startsWith("`") && name.endsWith("`")) {
          name = name.substring(1, name.length() - 1);
        }
        // Collapse the type body to a single line.
        final String typeBody =
            m.group(2)
                .replaceAll("(?m)^\\s*\\*\\s?", "")
                .replaceAll("\\s+", " ")
                .trim();
        result.add(
            new SpecInfo(
                SpecKind.FUNCTION,
                name,
                "",
                false,
                false,
                "basis",
                null,
                null,
                null,
                null,
                typeBody,
                "",
                null));
      }
    } else if (trimmed.startsWith("type ") || trimmed.startsWith("eqtype ")) {
      // type 'a foo  OR  type foo  OR  eqtype foo  OR  type foo = ...
      final String rest =
          trimmed.startsWith("eqtype ")
              ? trimmed.substring(7)
              : trimmed.substring(5);
      // Strip leading type vars like "'a " or "('a, 'b) "
      String s = rest.trim();
      if (s.startsWith("(")) {
        final int rp = s.indexOf(')');
        if (rp > 0) {
          s = s.substring(rp + 1).trim();
        }
      } else if (s.startsWith("'")) {
        final int sp = s.indexOf(' ');
        if (sp > 0) {
          s = s.substring(sp + 1).trim();
        }
      }
      final int firstNonId = findFirstNonIdent(s);
      final String name = firstNonId < 0 ? s : s.substring(0, firstNonId);
      if (!name.isEmpty()) {
        result.add(new SpecInfo(SpecKind.TYPE, name, "", false));
      }
    } else if (trimmed.startsWith("datatype ")) {
      // Same handling as type — extract the datatype name.
      final String rest = trimmed.substring(9).trim();
      String s = rest;
      if (s.startsWith("(")) {
        final int rp = s.indexOf(')');
        if (rp > 0) {
          s = s.substring(rp + 1).trim();
        }
      } else if (s.startsWith("'")) {
        final int sp = s.indexOf(' ');
        if (sp > 0) {
          s = s.substring(sp + 1).trim();
        }
      }
      final int firstNonId = findFirstNonIdent(s);
      final String name = firstNonId < 0 ? s : s.substring(0, firstNonId);
      if (!name.isEmpty()) {
        result.add(new SpecInfo(SpecKind.TYPE, name, "", false));
      }
    } else if (trimmed.startsWith("exception ")) {
      final String rest = trimmed.substring(10).trim();
      final int firstNonId = findFirstNonIdent(rest);
      final String name = firstNonId < 0 ? rest : rest.substring(0, firstNonId);
      if (!name.isEmpty()) {
        result.add(new SpecInfo(SpecKind.EXCEPTION, name, "", false));
      }
    }
  }

  private static int findFirstNonIdent(String s) {
    for (int i = 0; i < s.length(); i++) {
      final char c = s.charAt(i);
      if (!Character.isLetterOrDigit(c) && c != '_' && c != '\'') {
        return i;
      }
    }
    return -1;
  }

  /** Kind of a {@link SpecInfo}. */
  public enum SpecKind {
    FUNCTION,
    TYPE,
    EXCEPTION
  }

  /**
   * Information about a single spec parsed from a {@code .sig} file. Returned
   * by {@link #parseSpecs}.
   */
  public static class SpecInfo {
    public final SpecKind kind;
    /** Name as written in the spec (no {@code op } prefix). */
    public final String name;
    /** Canonicalized type stringification (empty for types and exceptions). */
    public final String type;
    /** {@code false} if the spec is commented out in the {@code .sig} file. */
    public final boolean implemented;
    /** True if the spec is annotated {@code [@@method]}. */
    public final boolean method;
    /**
     * Effective {@code specified} value: the {@code [@@specified "X"]} override
     * if present, otherwise the structure's {@code [@@@specified "X"]} default,
     * otherwise {@code "basis"}.
     */
    public final String specified;
    /** Prototype string from {@code [@@prototype "..."]}, or null. */
    public final @Nullable String prototype;
    /** Syntax keyword from {@code [@@syntax "..."]}, or null. */
    public final @Nullable String syntax;
    /** Extra text from {@code [@@extra "..."]}, or null. */
    public final @Nullable String extra;
    /**
     * Description text from a leading {@code (** ... *)} doc-comment (desugared
     * to {@code [@@doc "..."]}), or null.
     */
    public final @Nullable String description;
    /**
     * Type expression as written in the {@code .sig} source (e.g. {@code "'a
     * list -> int"}). Empty for types and exceptions.
     */
    public final String displayType;
    /**
     * For types: the full declaration as written in the {@code .sig} source
     * (e.g. {@code "datatype 'a list = nil | `::` of 'a * 'a list"} or {@code
     * "eqtype 'a bag"}). Empty for functions and exceptions.
     */
    public final String typeDecl;
    /**
     * For exceptions: the {@code of T} type, or null if the exception has no
     * payload.
     */
    public final @Nullable String exceptionType;

    SpecInfo(
        SpecKind kind,
        String name,
        String type,
        boolean implemented,
        boolean method,
        String specified,
        @Nullable String prototype,
        @Nullable String syntax,
        @Nullable String extra,
        @Nullable String description,
        String displayType,
        String typeDecl,
        @Nullable String exceptionType) {
      this.kind = requireNonNull(kind, "kind");
      this.name = requireNonNull(name, "name");
      this.type = requireNonNull(type, "type");
      this.implemented = implemented;
      this.method = method;
      this.specified = requireNonNull(specified, "specified");
      this.prototype = prototype;
      this.syntax = syntax;
      this.extra = extra;
      this.description = description;
      this.displayType = requireNonNull(displayType, "displayType");
      this.typeDecl = requireNonNull(typeDecl, "typeDecl");
      this.exceptionType = exceptionType;
    }

    SpecInfo(SpecKind kind, String name, String type, boolean implemented) {
      this(
          kind,
          name,
          type,
          implemented,
          false,
          "basis",
          null,
          null,
          null,
          null,
          "",
          "",
          null);
    }
  }

  /**
   * Converts signature name to structure. For example, signature "INTEGER" maps
   * to structure "Int".
   */
  private static String structureName(Ast.SignatureBind bind) {
    switch (bind.name.name) {
      case "BOOLEAN":
        return "Bool";
      case "INTEGER":
        return "Int";
      case "PP":
        return "PP";
      default:
        return toPascalCase(bind.name.name);
    }
  }

  private void verifyBuiltInType(Ast.SignatureBind bind, String structure) {
    // Build a map of the values in enum BuiltIn.
    final SortedMap<String, String> map = new TreeMap<>();
    for (BuiltIn builtIn : BuiltIn.values()) {
      if (structure.equals(builtIn.structure)) {
        // Skip datatype constructors for List (they're handled by PSEUDO_LIST)
        if ("List".equals(structure)
            && ("nil".equals(builtIn.mlName)
                || "op ::".equals(builtIn.mlName))) {
          continue;
        }
        map.put(alphaName(builtIn.mlName), str(builtIn));
      }
    }

    // Build a map of the values in the signature.
    // Only include specs that have corresponding BuiltIn entries.
    final SortedMap<String, String> map2 = new TreeMap<>();
    for (Ast.Spec spec : bind.specs) {
      // Unwrap AttributedSpec; ignore floating attributes for the BuiltIn
      // type check (they carry structure-level metadata only).
      final Ast.Spec inner =
          (spec.op == Op.ATTRIBUTED_SPEC)
              ? ((Ast.AttributedSpec) spec).spec
              : spec;
      if (inner.op == Op.SPEC_VAL) {
        final Ast.ValSpec valSpec = (Ast.ValSpec) inner;
        String signatureName = valSpec.name.name;
        // Normalize operator names by adding "op " prefix for lookup
        String lookupName = alphaName(signatureName);
        // Skip datatype constructors for List (they're handled by PSEUDO_LIST)
        if ("List".equals(structure)
            && ("nil".equals(lookupName) || "op ::".equals(lookupName))) {
          continue;
        }
        map2.put(lookupName, str(structure, valSpec));
      } else if (inner.op == Op.SPEC_EXCEPTION) {
        // Verify exception declarations
        final Ast.ExceptionSpec exnSpec = (Ast.ExceptionSpec) inner;
        verifyException(structure, exnSpec);
      } else if (inner.op == Op.SPEC_DATATYPE) {
        // Verify datatype constructors
        final Ast.DatatypeSpec datatypeSpec = (Ast.DatatypeSpec) inner;
        verifyDatatypeConstructors(structure, datatypeSpec);
      }
    }

    String left = String.join("", map.values());
    String right = String.join("", map2.values());
    assertThat(
        "If there is a line on the left but not the right, "
            + "you need to add a value to enum BuiltIn",
        left,
        is(right));
  }

  private static String alphaName(String signatureName) {
    switch (signatureName) {
        // lint: sort until '#}' where '#case '
      case "/":
        return "divide";
      case "^":
        return "caret";
      case "@":
        return "at";
      case "<":
        return "lt";
      case "<=":
        return "le";
      case ">":
        return "gt";
      case ">=":
        return "ge";
    }
    return isOperatorSymbol(signatureName)
        ? "op " + signatureName
        : signatureName;
  }

  /** Verifies that an exception spec matches a BuiltInExn entry. */
  private void verifyException(String structure, Ast.ExceptionSpec exnSpec) {
    String exnName = exnSpec.name.name;
    // Find matching exception in BuiltInExn
    boolean found = false;
    for (Codes.BuiltInExn builtInExn : Codes.BuiltInExn.values()) {
      if (structure.equals(builtInExn.structure)
          && exnName.equals(builtInExn.mlName())) {
        found = true;
        break;
      }
    }
    assertThat(
        format(
            "Exception %s in signature %s should exist in BuiltInExn",
            exnName, structure),
        found,
        is(true));
  }

  /**
   * Verifies that datatype constructors match entries in BuiltIn.Constructor.
   */
  private void verifyDatatypeConstructors(
      String structure, Ast.DatatypeSpec datatypeSpec) {
    String datatypeName = datatypeSpec.name.name;
    // Map structure+datatype name to Datatype enum
    String datatypeKey = structure + "." + datatypeName;

    for (Ast.TyCon tyCon : datatypeSpec.tyCons) {
      String constructorName = tyCon.id.name;
      // Try the spec name as written, with operator/keyword specials, and
      // then an all-uppercase fallback. Different datatypes use different
      // conventions (e.g. Date.month uses "Jan", Range uses "AT_LEAST").
      String[] candidates = {
        constructorName, normalizeConstructorName(constructorName),
      };

      BuiltIn.Constructor foundConstructor = null;
      for (String candidate : candidates) {
        for (BuiltIn.Constructor constructor : BuiltIn.Constructor.values()) {
          if (constructor.constructor.equals(candidate)) {
            foundConstructor = constructor;
            break;
          }
        }
        if (foundConstructor != null) {
          break;
        }
      }

      assertThat(
          format(
              "Constructor %s of datatype %s in signature %s should exist in BuiltIn.Constructor",
              constructorName, datatypeName, structure),
          foundConstructor,
          notNullValue());
    }
  }

  /**
   * Normalizes a constructor name for lookup in BuiltIn.Constructor. Most
   * constructors are converted to uppercase, but special operators like "::"
   * are mapped to their internal names like "CONS".
   */
  private static String normalizeConstructorName(String name) {
    // Special case for list cons operator
    if ("::".equals(name)) {
      return "CONS";
    }
    // Special case for nil
    if ("nil".equals(name)) {
      return "NIL";
    }
    // Default: uppercase
    return name.toUpperCase(Locale.ROOT);
  }

  /** Returns true if the name is an operator symbol (not alphanumeric). */
  private static boolean isOperatorSymbol(String name) {
    if (name.isEmpty()) {
      return false;
    }
    // Operator symbols are non-alphanumeric characters
    // Standard ML operators include: + - * / < > <= >= = <> :: @ ^ etc.
    for (int i = 0; i < name.length(); i++) {
      char c = name.charAt(i);
      if (Character.isLetterOrDigit(c) || c == '_' || c == '\'') {
        return false;
      }
    }
    return true;
  }

  private static String str(String structure, Ast.ValSpec s) {
    String name = s.name.name;
    // Normalize operator names by adding "op " prefix
    String normalizedName = alphaName(name);
    // Create a helper to convert types to strings while tracking type variables
    AstTypeStringifier stringifier = new AstTypeStringifier();
    return format(
        "%s_%s(\"%s\", \"%s\", ts -> %s)\n",
        requireNonNull(structure).toUpperCase(Locale.ROOT),
        toSnakeCase(normalizedName).toUpperCase(Locale.ROOT),
        structure,
        name,
        stringifier.str(s.type));
  }

  private String str(BuiltIn b) {
    Type type = b.typeFunction.apply(typeSystem);
    // Use TypeToStringConverter which tracks type variables as it converts
    TypeToStringConverter converter = new TypeToStringConverter();
    return format(
        "%s_%s(\"%s\", \"%s\", ts -> %s)\n",
        requireNonNull(b.structure).toUpperCase(Locale.ROOT),
        toSnakeCase(alphaName(b.mlName)).toUpperCase(Locale.ROOT),
        b.structure,
        b.mlName,
        converter.str(type));
  }

  /**
   * Helper class that converts Type to string representation while tracking
   * type variables and assigning them ordinals as they are encountered.
   */
  private static class TypeToStringConverter {
    private final Map<Integer, Integer> typeVarMap = new HashMap<>();

    String str(Type t) {
      switch (t.op()) {
        case FUNCTION_TYPE:
          final FnType fnType = (FnType) t;
          return format(
              "ts.fnType(%s, %s)",
              str(fnType.paramType), str(fnType.resultType));
        case TUPLE_TYPE:
          final TupleType tupleType = (TupleType) t;
          if (tupleType.argTypes.size() == 2) {
            return format(
                "ts.tupleType(%s, %s)",
                str(tupleType.argType(0)), str(tupleType.argType(1)));
          } else {
            StringBuilder sb = new StringBuilder("ts.tupleType(");
            for (int i = 0; i < tupleType.argTypes.size(); i++) {
              if (i > 0) {
                sb.append(", ");
              }
              sb.append(str(tupleType.argType(i)));
            }
            sb.append(")");
            return sb.toString();
          }
        case ID:
          return ((PrimitiveType) t).name();
        case DATA_TYPE:
          DataType dataType = (DataType) t;
          switch (dataType.name) {
            case "option":
              return format("ts.option(%s)", str(dataType.arguments.get(0)));
            case "order":
              return "ts.order()";
            case "list":
              return format("ts.list(%s)", str(dataType.arguments.get(0)));
            default:
              if (!dataType.arguments.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("ts.").append(dataType.name).append("(");
                for (int i = 0; i < dataType.arguments.size(); i++) {
                  if (i > 0) {
                    sb.append(", ");
                  }
                  sb.append(str(dataType.arguments.get(i)));
                }
                sb.append(")");
                return sb.toString();
              }
              return format("ts.lookup(\"%s\")", dataType.name());
          }
        case LIST:
          final ListType listType = (ListType) t;
          return format("ts.list(%s)", str(listType.elementType()));
        case RECORD_TYPE:
          final RecordType recordType = (RecordType) t;
          if (recordType.argNameTypes.isEmpty()) {
            return "ts.recordType(ImmutableSortedMap.of())";
          }
          StringBuilder sb =
              new StringBuilder("ts.recordType(ImmutableSortedMap.of(");
          boolean first = true;
          for (Map.Entry<String, Type> entry :
              recordType.argNameTypes.entrySet()) {
            if (!first) {
              sb.append(", ");
            }
            first = false;
            sb.append(
                format("\"%s\", %s", entry.getKey(), str(entry.getValue())));
          }
          sb.append("))");
          return sb.toString();
        case FORALL_TYPE:
          final ForallType forallType = (ForallType) t;
          return str(forallType.type);
        case MULTI_TYPE:
          // Overloaded type — collapse to the first variant for the
          // signature comparison. The .sig should declare the same first
          // variant; subsequent variants are implementation detail.
          final MultiType multiType = (MultiType) t;
          return str(multiType.types.get(0));
        case TY_VAR:
          final TypeVar typeVar = (TypeVar) t;
          final int normalizedOrdinal =
              typeVarMap.computeIfAbsent(
                  typeVar.ordinal, k -> typeVarMap.size());
          return format("h.get(%d)", normalizedOrdinal);
        default:
          throw new UnsupportedOperationException(
              format("type: %s (op=%s, class=%s)", t, t.op(), t.getClass()));
      }
    }
  }

  /**
   * Helper class that converts Ast.Type to string representation while tracking
   * type variables and assigning them ordinals as they are encountered.
   */
  private static class AstTypeStringifier {
    private final Map<String, Integer> tyVarMap = new HashMap<>();

    String str(Ast.Type t) {
      switch (t.op) {
        case FUNCTION_TYPE:
          final Ast.FunctionType fnType = (Ast.FunctionType) t;
          return format(
              "ts.fnType(%s, %s)",
              str(fnType.paramType), str(fnType.resultType));
        case TUPLE_TYPE:
          final Ast.TupleType tupleType = (Ast.TupleType) t;
          if (tupleType.types.size() == 2) {
            return format(
                "ts.tupleType(%s, %s)",
                str(tupleType.types.get(0)), str(tupleType.types.get(1)));
          } else {
            // Handle tuples with more than 2 elements
            StringBuilder sb = new StringBuilder("ts.tupleType(");
            for (int i = 0; i < tupleType.types.size(); i++) {
              if (i > 0) {
                sb.append(", ");
              }
              sb.append(str(tupleType.types.get(i)));
            }
            sb.append(")");
            return sb.toString();
          }
        case NAMED_TYPE:
          Ast.NamedType namedType = (Ast.NamedType) t;
          switch (namedType.name) {
            case "bool":
            case "char":
            case "int":
            case "real":
            case "string":
            case "unit":
            case "word":
              // The 'int' type is in a constant called 'INT'.
              return namedType.name.toUpperCase(Locale.ROOT);
            case "option":
              return format("ts.option(%s)", str(namedType.types.get(0)));
            case "order":
              return "ts.order()";
            case "list":
              return format("ts.list(%s)", str(namedType.types.get(0)));
            default:
              // Generic named type with type arguments
              if (!namedType.types.isEmpty()) {
                StringBuilder sb = new StringBuilder();
                sb.append("ts.").append(namedType.name).append("(");
                for (int i = 0; i < namedType.types.size(); i++) {
                  if (i > 0) {
                    sb.append(", ");
                  }
                  sb.append(str(namedType.types.get(i)));
                }
                sb.append(")");
                return sb.toString();
              }
              return format("ts.lookup(\"%s\")", namedType.name);
          }
        case RECORD_TYPE:
          final Ast.RecordType recordType = (Ast.RecordType) t;
          if (recordType.fieldTypes.isEmpty()) {
            return "ts.recordType(ImmutableSortedMap.of())";
          }
          // Sort fields alphabetically to match RecordType's internal ordering
          SortedMap<String, Ast.Type> sortedFields =
              new TreeMap<>(recordType.fieldTypes);
          StringBuilder sb =
              new StringBuilder("ts.recordType(ImmutableSortedMap.of(");
          boolean first = true;
          for (Map.Entry<String, Ast.Type> entry : sortedFields.entrySet()) {
            if (!first) {
              sb.append(", ");
            }
            first = false;
            sb.append(
                format("\"%s\", %s", entry.getKey(), str(entry.getValue())));
          }
          sb.append("))");
          return sb.toString();
        case TY_VAR:
          final Ast.TyVar tyVar = (Ast.TyVar) t;
          // Assign ordinal to type variable if not already assigned
          final int ordinal =
              tyVarMap.computeIfAbsent(tyVar.name, k -> tyVarMap.size());
          return format("h.get(%d)", ordinal);
        default:
          throw new UnsupportedOperationException(format("type: %s", t));
      }
    }
  }
}

// End SignatureChecker.java
