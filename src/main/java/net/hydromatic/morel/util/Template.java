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

import static java.util.Objects.requireNonNull;

import java.util.function.IntFunction;

/**
 * A string in which a number in braces stands for an argument, and a doubled
 * opening brace for a literal one. The Spark translator's function table holds
 * templates of SQL, but nothing here knows that.
 *
 * <p>{@link #expand} asks for each argument as it meets it, rather than being
 * handed them all, so an argument that the template does not use is never
 * rendered -- and an argument that cannot be rendered only matters if the
 * template wants it. The callback renders one argument, and if that argument is
 * itself something with a template, rendering it comes back here; that is the
 * whole of the recursion.
 *
 * <p>A template is checked when it is made, so that a malformed one is refused
 * where it is written rather than where it is used.
 */
public class Template {
  private final String pattern;
  private final int argCount;

  private Template(String pattern) {
    this.pattern = requireNonNull(pattern, "pattern");
    final int[] max = {-1};
    // Walking the pattern checks it, and tells us the arguments it uses.
    scan(
        new Visitor() {
          @Override
          public void literal(char c) {}

          @Override
          public void argument(int i) {
            max[0] = Math.max(max[0], i);
          }
        });
    this.argCount = max[0] + 1;
  }

  /** Creates a template, checking that it is well formed. */
  public static Template of(String pattern) {
    return new Template(pattern);
  }

  /**
   * Expands this template, calling {@code arg} for the text of each argument it
   * uses, in the order it meets them.
   */
  public String expand(IntFunction<String> arg) {
    final StringBuilder b = new StringBuilder();
    scan(
        new Visitor() {
          @Override
          public void literal(char c) {
            b.append(c);
          }

          @Override
          public void argument(int i) {
            b.append(arg.apply(i));
          }
        });
    return b.toString();
  }

  /**
   * Expands this template with arguments already rendered.
   *
   * <p>The count must be the one the template asks for, because an argument too
   * many is a caller that thinks the template uses it.
   */
  public String expand(String... args) {
    if (args.length != argCount) {
      throw new IllegalArgumentException(
          "template ["
              + pattern
              + "] takes "
              + argCount
              + " arguments, got "
              + args.length);
    }
    return expand(i -> args[i]);
  }

  /**
   * Returns how many arguments this template uses, which is one more than the
   * greatest placeholder in it. A template that skips an argument still counts
   * it, so a template whose only placeholder is argument one wants two.
   */
  public int argCount() {
    return argCount;
  }

  @Override
  public String toString() {
    return pattern;
  }

  /** Walks the pattern, telling {@code visitor} what it finds. */
  private void scan(Visitor visitor) {
    for (int i = 0; i < pattern.length(); i++) {
      final char c = pattern.charAt(i);
      if (c != '{') {
        visitor.literal(c);
        continue;
      }
      if (i + 1 < pattern.length() && pattern.charAt(i + 1) == '{') {
        visitor.literal('{');
        ++i;
        continue;
      }
      final int close = pattern.indexOf('}', i);
      if (close < 0) {
        throw new IllegalArgumentException(
            "unclosed placeholder in template [" + pattern + "]");
      }
      visitor.argument(ordinal(pattern.substring(i + 1, close)));
      i = close;
    }
  }

  private int ordinal(String s) {
    try {
      final int i = Integer.parseInt(s);
      if (i < 0) {
        throw new NumberFormatException(s);
      }
      return i;
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          "placeholder ["
              + s
              + "] in template ["
              + pattern
              + "] is not an argument number");
    }
  }

  /** What {@link #scan} finds in a pattern. */
  private interface Visitor {
    void literal(char c);

    void argument(int i);
  }
}

// End Template.java
