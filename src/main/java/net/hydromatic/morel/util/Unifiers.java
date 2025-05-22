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

import static java.lang.Character.isDigit;
import static java.lang.Character.isUpperCase;
import static java.util.stream.Collectors.joining;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Utilities for unification.
 *
 * @see Unifier
 */
public abstract class Unifiers {
  /**
   * Given several pairs of terms, generates a program that will create those
   * pairs of terms.
   *
   * <p>This is useful if you wish to create a unit test for a {@link Unifier}.
   */
  public static void dump(PrintWriter pw, Iterable<Unifier.TermTerm> pairs) {
    new Dumper(pw).dumpAll(pairs);
  }

  /** Work space for {@link #dump(PrintWriter, Iterable)}. */
  private static class Dumper {
    final Map<Unifier.Term, String> terms = new HashMap<>();
    final Set<String> names = new HashSet<>();
    final PrintWriter pw;

    Dumper(PrintWriter pw) {
      this.pw = pw;
    }

    void dumpAll(Iterable<? extends Unifier.TermTerm> pairs) {
      pw.printf("List<Unifier.TermTerm> pairs = new ArrayList<>();%n");
      for (Unifier.TermTerm pair : pairs) {
        String left = lookup(pair.left);
        String right = lookup(pair.right);
        pw.printf("pairs.add(new Unifier.TermTerm(%s, %s));\n", left, right);
      }
      pw.flush();
    }

    /** Registers a variable name, ensuring that it is valid and unique. */
    String var(String v) {
      if (v.isEmpty()) {
        v = "v";
      }
      // Ensure that variable starts with a lower-case letter.
      if (isUpperCase(v.charAt(0))) {
        v = v.toLowerCase(Locale.ROOT);
      }
      // If name is not already registered, register and return it.
      if (names.add(v)) {
        return v;
      }
      // Remove numeric suffix, e.g. "v123" -> "v"
      while (!v.isEmpty() && isDigit(v.charAt(v.length() - 1))) {
        v = v.substring(0, v.length() - 1);
      }
      // Add a numeric suffix so that it is unique, e.g. "v" -> "v3" if "v1" and
      // "v2" are already registered.
      for (int i = 1; ; ++i) {
        String v2 = v + i;
        if (names.add(v2)) {
          return v2;
        }
      }
    }

    /** Looks up the variable holding a term, registering it if new. */
    String lookup(Unifier.Term term) {
      String s = terms.get(term);
      if (s != null) {
        return s;
      }
      // Put a sentinel value into the map, so that we know the term is being
      // registered.
      terms.put(term, "");
      s = register(term);
      if (isUpperCase(s.charAt(0))) {
        s = s.toLowerCase(Locale.ROOT);
      }
      terms.put(term, s);
      return s;
    }

    /** Registers a term and returns a unique variable name for it. */
    private String register(Unifier.Term term) {
      String v;
      if (term instanceof Unifier.Variable) {
        Unifier.Variable variable = (Unifier.Variable) term;
        v = var(variable.name);
        if (variable.name.matches("T[0-9]+")) {
          pw.printf(
              "final Unifier.Variable %s = unifier.variable(%d);%n",
              v, Integer.parseInt(variable.name.substring(1)));
        } else {
          pw.printf(
              "final Unifier.Variable %s = unifier.variable(\"%s\");%n", v, v);
        }
      } else {
        Unifier.Sequence sequence = (Unifier.Sequence) term;
        if (sequence.terms.isEmpty()) {
          v = var(sequence.operator);
          pw.printf(
              "final Unifier.Term %s = unifier.atom(\"%s\");%n",
              v, sequence.operator);
        } else {
          v = var(sequence.operator) + "1";
          pw.printf(
              "final Unifier.Sequence %s = unifier.apply(\"%s\", %s);%n",
              v,
              sequence.operator,
              sequence.terms.stream().map(this::lookup).collect(joining(", ")));
        }
      }
      return v;
    }
  }
}

// End Unifiers.java
