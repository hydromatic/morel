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
package net.hydromatic.morel.eval;

import com.google.common.base.CaseFormat;
import com.google.common.collect.ImmutableMap;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/** Property.
 *
 * @see Session#map
 */
public enum Prop {
  /** File property "directory" is the path of the directory that the
   * {@code file} variable maps to in this connection.
   *
   * <p>The default value is the empty string;
   * many tests use the "src/test/resources" directory;
   * when launched via the {@code morel} shell script, the default value is the
   * shell's current directory. */
  DIRECTORY("directory", File.class, new File("")),

  /** File property "scriptDirectory" is the path of the directory where the
   * {@code use} command looks for scripts. When running a script, it is
   * generally set to the directory that contains the script. */
  SCRIPT_DIRECTORY("scriptDirectory", File.class, new File("")),

  /** Boolean property "hybrid" controls whether to try to create a hybrid
   * execution plan that uses Apache Calcite relational algebra wherever
   * possible. Default is false. */
  HYBRID("hybrid", Boolean.class, false),

  /** Maximum number of inlining passes. */
  INLINE_PASS_COUNT("inlinePassCount", Integer.class, 5),

  /** Boolean property "matchCoverageEnabled" controls whether to check the
   * coverage of patterns. If true (the default), Morel warns if patterns are
   * redundant and gives errors if patterns are not exhaustive. If false,
   * Morel does not analyze pattern coverage, and therefore will not give
   * warnings or errors. */
  MATCH_COVERAGE_ENABLED("matchCoverageEnabled", Boolean.class, true),

  /** Integer property "optionalInt" is for testing. Default is null. */
  OPTIONAL_INT("optionalInt", Integer.class, null),

  /** Integer property "printDepth" controls printing.
   * The depth of nesting of recursive data structure at which ellipsis begins.
   *
   * <p>It is based upon the "printDepth" property in the
   * <a href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL signature</a>
   * of the Standard Basis Library.
   * Default is 5. */
  PRINT_DEPTH("printDepth", Integer.class, 5),

  /** Integer property "printLength" controls printing.
   * The length of lists at which ellipsis begins.
   *
   * <p>It is based upon the "printLength" property in the
   * <a href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL signature</a>
   * of the Standard Basis Library.
   *
   * <p>Default is 12. */
  PRINT_LENGTH("printLength", Integer.class, 12),

  /** Boolean property "relationalize" is
   * whether to convert to relational algebra.
   * Default is false. */
  RELATIONALIZE("relationalize", Boolean.class, false),

  /** Integer property "stringDepth" is
   * the length of strings at which ellipsis begins.
   *
   * <p>It is based upon the "stringDepth" property in the
   * <a href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL signature</a>
   * of the Standard Basis Library.
   * Default is 70. */
  STRING_DEPTH("stringDepth", Integer.class, 70),

  /** Integer property "lineWidth" controls printing.
   * The length at which lines are wrapped.
   *
   * <p>It is based upon the "linewidth" property in the
   * <a href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL signature</a>
   * of the Standard Basis Library.
   * Default is 79. */
  LINE_WIDTH("lineWidth", Integer.class, 79);

  public final String camelName;
  private final Class<?> type;
  private final Object defaultValue;

  public static final ImmutableMap<String, Prop> BY_NAME;

  static {
    final Map<String, Prop> map = new LinkedHashMap<>();
    for (Prop value : values()) {
      map.put(value.name(), value);
      map.put(value.camelName, value);
    }
    BY_NAME = ImmutableMap.copyOf(map);
  }

  Prop(String camelName, Class<?> type, Object defaultValue) {
    this.camelName = camelName;
    this.type = type;
    this.defaultValue = defaultValue;
    assert CaseFormat.LOWER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, camelName)
        .equals(name());
    if (type == Boolean.class) {
      assert defaultValue == null || defaultValue.getClass() == type;
    } else if (type == Integer.class) {
      assert defaultValue == null || defaultValue.getClass() == type;
    } else if (type == String.class) {
      assert defaultValue == null || defaultValue.getClass() == type;
    } else if (type == File.class) {
      assert defaultValue == null || defaultValue.getClass() == type;
    } else {
      throw new AssertionError("not a valid property type: "
          + type);
    }
  }

  /** Looks up a property by name. Throws if not found; never returns null. */
  public static Prop lookup(String propName) {
    Prop prop = BY_NAME.get(propName);
    if (prop == null) {
      throw new RuntimeException("property " + propName + " not found");
    }
    return prop;
  }

  /** Returns the value of a property. */
  public Object get(Map<Prop, Object> map) {
    Object o = map.get(this);
    return o != null ? o : defaultValue;
  }

  /** Returns the value of a boolean property. */
  public boolean booleanValue(Map<Prop, Object> map) {
    assert type == Boolean.class;
    Object o = map.get(this);
    return this.<Boolean>typeValue(o);
  }

  /** Returns the value of an integer property. */
  public int intValue(Map<Prop, Object> map) {
    assert type == Integer.class;
    Object o = map.get(this);
    return this.<Integer>typeValue(o);
  }

  /** Returns the value of a string property. */
  public String stringValue(Map<Prop, Object> map) {
    assert type == String.class;
    Object o = map.get(this);
    return this.typeValue(o);
  }

  /** Returns the value of a file property. */
  public File fileValue(Map<Prop, Object> map) {
    assert type == File.class;
    Object o = map.get(this);
    return this.typeValue(o);
  }

  @SuppressWarnings("unchecked")
  private <T> T typeValue(Object o) {
    if (o == null) {
      if (defaultValue == null) {
        throw new RuntimeException("no value for property " + camelName
            + " and no default value");
      }
      return (T) defaultValue;
    }
    return (T) o;
  }

  /** Sets the value of a property.
   * Checks that its type is valid. */
  public void set(Map<Prop, Object> map, Object value) {
    if (value != null && !type.isInstance(value)) {
      throw new RuntimeException("value for property must have type " + type);
    }
    map.put(this, value);
  }

  /** Removes the value of this property from a map, returning the previous
   * value or null. */
  public Object remove(Map<Prop, Object> map) {
    return map.remove(this);
  }
}

// End Prop.java
