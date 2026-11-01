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
package net.hydromatic.morel.spark;

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableMap;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.parse.MorelParserImpl;
import net.hydromatic.morel.util.Template;
import org.jspecify.annotations.Nullable;

/**
 * How each Morel operator reaches Spark: the table that {@code functions.sml}
 * holds as data, read for the translator's use.
 *
 * <p>The table is data, in Morel, so that every port can share one copy and one
 * set of tests. It is read rather than evaluated: every entry is a record of
 * string literals, so the parse tree is the value, and reading it does not
 * require an evaluator.
 *
 * <p>{@code SparkFunctionsTest} checks the file itself -- that every name is a
 * built-in, every kind is known, and every template uses its arguments. This
 * class is only concerned with getting it into memory.
 */
public class SparkFunctionTable {
  /** Where the table lives, on the class path. */
  private static final String RESOURCE =
      "/net/hydromatic/morel/spark/functions.sml";

  private static final SparkFunctionTable INSTANCE = load();

  private final ImmutableMap<String, Entry> entries;
  private final ImmutableMap<BuiltIn, Entry> byBuiltIn;

  private SparkFunctionTable(Map<String, Entry> entries) {
    this.entries = ImmutableMap.copyOf(entries);
    final Map<BuiltIn, Entry> byBuiltIn = new LinkedHashMap<>();
    entries.forEach(
        (name, entry) -> {
          final BuiltIn builtIn = builtIn(name);
          if (builtIn != null) {
            byBuiltIn.put(builtIn, entry);
          }
        });
    // An "op X" row covers every type instance of the operator: the tree
    // carries the instance that overload resolution chose, "Real.*" rather
    // than the "*" the programmer wrote. A row naming the instance wins,
    // which is how "Real./" stays distinct from "op div".
    for (BuiltIn builtIn : BuiltIn.values()) {
      if (!byBuiltIn.containsKey(builtIn)) {
        final Entry entry = entries.get("op " + builtIn.mlName);
        if (entry != null) {
          byBuiltIn.put(builtIn, entry);
        }
      }
    }
    this.byBuiltIn = ImmutableMap.copyOf(byBuiltIn);
  }

  /**
   * Returns the built-in that the table calls {@code name}, or null if there is
   * none. {@code SparkFunctionsTest} requires that every name in the file
   * resolves, so a null here means the file and the built-ins have drifted
   * apart, and that test will say so; this class leaves the entry out rather
   * than failing to load.
   */
  private static @Nullable BuiltIn builtIn(String name) {
    final int dot = name.indexOf('.');
    if (dot < 0) {
      return BuiltIn.BY_ML_NAME.get(name);
    }
    final String structureName = name.substring(0, dot);
    final String memberName = name.substring(dot + 1);
    if (structureName.equals("$")) {
      // Internal operators are deliberately absent from BY_STRUCTURE, so
      // that nothing reaches them by name by accident. The table names
      // them anyway, because they are what the core tree carries. Their
      // own names are inconsistent about the sigil -- "andalso" but
      // "$current" -- so accept the member with or without it.
      for (BuiltIn builtIn : BuiltIn.values()) {
        if (builtIn.structure.equals("$")
            && (builtIn.mlName.equals(memberName)
                || builtIn.mlName.equals("$" + memberName))) {
          return builtIn;
        }
      }
      return null;
    }
    final BuiltIn.Structure structure = BuiltIn.BY_STRUCTURE.get(structureName);
    return structure == null ? null : structure.memberMap.get(memberName);
  }

  /** Returns the table. */
  public static SparkFunctionTable instance() {
    return INSTANCE;
  }

  /**
   * Returns how {@code morelName} reaches Spark, or null if the table does not
   * mention it. A name the table does not mention is as untranslatable as one
   * it calls {@link Kind#UNSUPPORTED}, but the distinction is worth a different
   * message: one is a gap in the table, the other a decision recorded in it.
   */
  public @Nullable Entry get(String morelName) {
    return entries.get(morelName);
  }

  /**
   * Returns how {@code builtIn} reaches Spark, or null if the table does not
   * mention it. This is the lookup the translator makes: it has a {@link
   * BuiltIn} in hand, from a function literal in the core tree, not the name
   * the table writes.
   */
  public @Nullable Entry get(BuiltIn builtIn) {
    return byBuiltIn.get(builtIn);
  }

  /** Returns every entry, in the order the file lists them. */
  public Collection<Entry> entries() {
    return entries.values();
  }

  private static SparkFunctionTable load() {
    try (InputStream stream =
        SparkFunctionTable.class.getResourceAsStream(RESOURCE)) {
      requireNonNull(stream, RESOURCE);
      try (Reader reader =
          new InputStreamReader(stream, StandardCharsets.UTF_8)) {
        final StringBuilder b = new StringBuilder();
        final char[] buf = new char[4096];
        for (int n; (n = reader.read(buf)) >= 0; ) {
          b.append(buf, 0, n);
        }
        return parse(b.toString());
      }
    } catch (IOException e) {
      throw new UncheckedIOException("cannot read " + RESOURCE, e);
    }
  }

  private static SparkFunctionTable parse(String source) {
    final MorelParserImpl parser =
        new MorelParserImpl(new StringReader(source));
    final AstNode node = parser.statementEofSafe();
    if (!(node instanceof Ast.ListExp)) {
      throw new IllegalStateException(
          RESOURCE + ": expected a list, got " + node.op);
    }
    final Map<String, Entry> entries = new LinkedHashMap<>();
    for (Ast.Exp arg : ((Ast.ListExp) node).args) {
      final Entry entry = entry(arg);
      if (entries.put(entry.morel, entry) != null) {
        throw new IllegalStateException(
            RESOURCE + ": duplicate entry " + entry.morel);
      }
    }
    return new SparkFunctionTable(entries);
  }

  private static Entry entry(Ast.Exp exp) {
    if (!(exp instanceof Ast.Record)) {
      throw new IllegalStateException(
          RESOURCE + ": expected a record, got " + exp.op);
    }
    final Map<String, String> fields = new LinkedHashMap<>();
    ((Ast.Record) exp)
        .args.forEach((id, value) -> fields.put(id.name, string(value)));
    return new Entry(
        field(fields, "morel"),
        kind(field(fields, "kind")),
        field(fields, "spark"),
        field(fields, "divergence"));
  }

  private static String field(Map<String, String> fields, String name) {
    final String value = fields.get(name);
    if (value == null) {
      throw new IllegalStateException(
          RESOURCE + ": entry has no '" + name + "' field");
    }
    return value;
  }

  private static String string(Ast.Exp exp) {
    if (exp.op != Op.STRING_LITERAL) {
      throw new IllegalStateException(
          RESOURCE + ": expected a string literal, got " + exp.op);
    }
    return (String) ((Ast.Literal) exp).value;
  }

  private static Kind kind(String kind) {
    try {
      return Kind.valueOf(kind.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(RESOURCE + ": unknown kind " + kind);
    }
  }

  /** How an operator reaches Spark. */
  public enum Kind {
    /** An aggregate function, applied over a group. */
    AGGREGATE,
    /** Spark has the operator, under the name the entry gives. */
    DIRECT,
    /**
     * The entry gives an expression template, with {@code $0} for the first
     * argument.
     */
    REWRITTEN,
    /** The operator becomes a subquery. */
    SUBQUERY,
    /** Spark cannot do it, so a query that uses it cannot be prepared. */
    UNSUPPORTED
  }

  /** One row of the table. */
  public static class Entry {
    /** The Morel operator, as the table names it, such as {@code "op +"}. */
    public final String morel;
    /** How it reaches Spark. */
    public final Kind kind;
    /** Spark's name for it, or the template that replaces it. */
    public final String spark;
    /** Known semantic divergence, or empty if the two agree. */
    public final String divergence;

    /**
     * The template, if this entry is rewritten; null otherwise. Making it here
     * means a malformed template fails when the table loads rather than when
     * some query reaches the operator.
     */
    public final @Nullable Template template;

    Entry(String morel, Kind kind, String spark, String divergence) {
      this.morel = requireNonNull(morel, "morel");
      this.kind = requireNonNull(kind, "kind");
      this.spark = requireNonNull(spark, "spark");
      this.divergence = requireNonNull(divergence, "divergence");
      this.template = kind == Kind.REWRITTEN ? Template.of(spark) : null;
    }

    @Override
    public String toString() {
      return morel + " -> " + spark + " (" + kind + ")";
    }
  }
}

// End SparkFunctionTable.java
