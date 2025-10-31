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

import static com.google.common.collect.ImmutableList.sortedCopyOf;
import static java.util.Objects.requireNonNull;
import static net.hydromatic.morel.util.Static.filterEager;
import static net.hydromatic.morel.util.Static.transformEager;
import static org.apache.calcite.util.Util.first;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.MappingIterator;
import com.fasterxml.jackson.dataformat.toml.TomlMapper;
import com.google.common.collect.Ordering;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import net.hydromatic.morel.Main;
import net.hydromatic.morel.TestUtils;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Generates code from metadata. */
public class Generation {
  private Generation() {}

  /**
   * Reads the {@code functions.toml} file and generates a table of function
   * definitions into {@code reference.md}.
   */
  @SuppressWarnings("unchecked")
  public static void generateFunctionTable(PrintWriter pw) throws IOException {
    final URL inUrl = Main.class.getResource("/functions.toml");
    assertThat(inUrl, notNullValue());
    final File file = TestUtils.urlToFile(inUrl);

    final TomlMapper mapper = new TomlMapper();
    try (MappingIterator<Object> it =
        mapper.readerForMapOf(Object.class).readValues(file)) {
      while (it.hasNextValue()) {
        final Map<String, Object> row = (Map<String, Object>) it.nextValue();
        final List<FnDef> fnDefs =
            transformEager(
                (List<Map<String, Object>>) row.get("functions"),
                FnDef::create);

        // The functions in the toml file must be sorted by name.
        // This reduces the chance of merge conflicts.
        final List<String> names = new ArrayList<>();
        for (FnDef fnDef : fnDefs) {
          names.add(fnDef.structure + '.' + fnDef.name);
        }
        if (!Ordering.natural().isOrdered(names)) {
          fail(
              "Names are not sorted\n"
                  + TestUtils.diffLines(names, sortedCopyOf(names)));
        }

        // Build sorted list of functions. First add the ones with ordinals,
        // sorted by ordinal. Then add the rest, sorted by name.
        final List<FnDef> sortedFnDefs = new ArrayList<>();
        for (FnDef fnDef : fnDefs) {
          if (fnDef.ordinal >= 0) {
            sortedFnDefs.add(fnDef);
          }
        }
        sortedFnDefs.sort(
            Comparator.<FnDef, String>comparing(f -> f.structure)
                .thenComparingInt(f -> f.ordinal));
        for (FnDef fnDef : fnDefs) {
          if (fnDef.ordinal <= 0) {
            int i =
                findMax(
                    sortedFnDefs,
                    f ->
                        f.qualifiedName().compareTo(fnDef.qualifiedName()) < 0);
            sortedFnDefs.add(i, fnDef);
          }
        }

        List<FnDef> implemented =
            filterEager(sortedFnDefs, fn -> fn.implemented);
        generateTable(pw, implemented);

        List<FnDef> notImplemented =
            filterEager(sortedFnDefs, fn -> !fn.implemented);
        if (!notImplemented.isEmpty()) {
          pw.printf("Not yet implemented%n");
          generateTable(pw, notImplemented);
        }
      }
    }
  }

  /** Returns the first index of the list where the predicate is false. */
  private static <E> int findMax(List<E> list, Predicate<E> predicate) {
    for (int i = 0; i < list.size(); i++) {
      E e = list.get(i);
      if (!predicate.test(e)) {
        return i;
      }
    }
    return -1;
  }

  private static void generateTable(PrintWriter pw, List<FnDef> functions) {
    pw.printf("%n");
    row(pw, "Name", "Type", "Description");
    row(pw, "----", "----", "-----------");
    for (FnDef function : functions) {
      String name2 = munge(function.structure + '.' + function.name);
      String type2 = munge(function.type);
      String description2 =
          munge(
              function.description.startsWith("As ")
                  ? function.description
                  : '"' + function.prototype + "\" " + function.description);
      if (function.extra != null) {
        description2 += " " + function.extra.trim();
      }
      row(pw, name2, type2, description2);
    }
    pw.printf("%n");
  }

  private static void row(
      PrintWriter pw, String name, String type, String description) {
    pw.printf("| %s | %s | %s |\n", name, type, description);
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
    final int ordinal;

    FnDef(
        String structure,
        String name,
        String type,
        String prototype,
        String description,
        String extra,
        boolean implemented,
        int ordinal) {
      this.structure = requireNonNull(structure, "structure");
      this.name = requireNonNull(name, "name");
      this.type = requireNonNull(type, "type");
      this.prototype = requireNonNull(prototype, "prototype");
      this.description = requireNonNull(description, "description");
      this.extra = extra;
      this.implemented = implemented;
      this.ordinal = ordinal;
    }

    String qualifiedName() {
      return structure + '.' + name;
    }

    static FnDef create(Map<String, Object> map) {
      return new FnDef(
          (String) map.get("structure"),
          (String) map.get("name"),
          (String) map.get("type"),
          (String) map.get("prototype"),
          (String) map.get("description"),
          (String) map.get("extra"),
          first((Boolean) map.get("implemented"), true),
          map.containsKey("ordinal") ? (Integer) map.get("ordinal") : -1);
    }
  }
}

// End Generation.java
