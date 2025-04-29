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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.CaseFormat;
import com.google.common.base.Enums;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import java.io.File;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Property.
 *
 * @see Session#map
 */
public enum Prop {
  /**
   * File property "directory" is the path of the directory that the {@code
   * file} variable maps to in this connection.
   *
   * <p>The default value is the empty string; many tests use the
   * "src/test/resources" directory; when launched via the {@code morel} shell
   * script, the default value is the shell's current directory.
   */
  DIRECTORY("directory", File.class, true, new File("")),

  /**
   * File property "scriptDirectory" is the path of the directory where the
   * {@code use} command looks for scripts. When running a script, it is
   * generally set to the directory that contains the script.
   */
  SCRIPT_DIRECTORY("scriptDirectory", File.class, true, new File("")),

  /**
   * Boolean property "hybrid" controls whether to try to create a hybrid
   * execution plan that uses Apache Calcite relational algebra wherever
   * possible. Default is false.
   */
  HYBRID("hybrid", Boolean.class, true, false),

  /** Maximum number of inlining passes. */
  INLINE_PASS_COUNT("inlinePassCount", Integer.class, true, 5),

  /**
   * Boolean property "matchCoverageEnabled" controls whether to check the
   * coverage of patterns. If true (the default), Morel warns if patterns are
   * redundant and gives errors if patterns are not exhaustive. If false, Morel
   * does not analyze pattern coverage, and therefore will not give warnings or
   * errors.
   */
  MATCH_COVERAGE_ENABLED("matchCoverageEnabled", Boolean.class, true, true),

  /** Integer property "optionalInt" is for testing. Default is null. */
  OPTIONAL_INT("optionalInt", Integer.class, false, null),

  /**
   * String property "output" controls how values are printed in the shell.
   * Default is "classic".
   */
  OUTPUT("output", Output.class, true, Output.CLASSIC),

  /**
   * Integer property "printDepth" controls printing. The depth of nesting of
   * recursive data structure at which ellipsis begins.
   *
   * <p>It is based upon the "printDepth" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library. Default is 5.
   */
  PRINT_DEPTH("printDepth", Integer.class, true, 5),

  /**
   * Integer property "printLength" controls printing. The length of lists at
   * which ellipsis begins.
   *
   * <p>It is based upon the "printLength" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library.
   *
   * <p>Default is 12.
   */
  PRINT_LENGTH("printLength", Integer.class, true, 12),

  /**
   * Boolean property "relationalize" is whether to convert to relational
   * algebra. Default is false.
   */
  RELATIONALIZE("relationalize", Boolean.class, true, false),

  /**
   * Integer property "stringDepth" is the length of strings at which ellipsis
   * begins.
   *
   * <p>It is based upon the "stringDepth" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library. Default is 70.
   */
  STRING_DEPTH("stringDepth", Integer.class, true, 70),

  /**
   * Integer property "lineWidth" controls printing. The length at which lines
   * are wrapped.
   *
   * <p>It is based upon the "linewidth" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library. Default is 79.
   */
  LINE_WIDTH("lineWidth", Integer.class, true, 79);

  public final String camelName;
  private final Class<?> type;
  private final boolean required;
  private final Object defaultValue;

  /**
   * Map of all properties, keyed by both {@link #name()} and {@link
   * #camelName}.
   */
  public static final ImmutableMap<String, Prop> BY_NAME;

  /** List of all properties sorted by {@link #camelName}. */
  public static final List<Prop> BY_CAMEL_NAME;

  static {
    final List<Prop> list = Arrays.asList(values());
    final Ordering<Prop> ordering =
        Ordering.from(Comparator.comparing((Prop o) -> o.camelName));
    BY_CAMEL_NAME = ordering.sortedCopy(list);

    final Map<String, Prop> map = new LinkedHashMap<>();
    for (Prop value : BY_CAMEL_NAME) {
      map.put(value.name(), value);
      map.put(value.camelName, value);
    }
    BY_NAME = ImmutableMap.copyOf(map);
  }

  Prop(String camelName, Class<?> type, boolean required, Object defaultValue) {
    this.camelName = camelName;
    this.type = type;
    this.required = required;
    this.defaultValue = defaultValue;
    checkArgument(
        CaseFormat.LOWER_CAMEL
            .to(CaseFormat.UPPER_UNDERSCORE, camelName)
            .equals(name()));
    if (defaultValue == null) {
      checkArgument(
          !required, "required property %s must have default value", camelName);
    } else {
      checkArgument(validValue(type, defaultValue));
    }
  }

  private boolean validValue(Class<?> type, Object value) {
    if (type == Boolean.class
        || type == File.class
        || type == Integer.class
        || type == String.class
        || type.isEnum()) {
      return type.isInstance(value);
    }
    return false;
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

  /** Throws if the requested type does not match this property's type. */
  private void checkType(Class<?> requestedType) {
    checkArgument(
        type == requestedType,
        "invalid type %s for property %s",
        type,
        camelName);
  }

  /** Returns the value of a boolean property. */
  public boolean booleanValue(Map<Prop, Object> map) {
    checkType(Boolean.class);
    Object o = map.get(this);
    return this.<Boolean>typeValue(o);
  }

  /** Returns the value of an integer property. */
  public int intValue(Map<Prop, Object> map) {
    checkType(Integer.class);
    Object o = map.get(this);
    return this.<Integer>typeValue(o);
  }

  /** Returns the value of a string property. */
  public String stringValue(Map<Prop, Object> map) {
    checkType(String.class);
    Object o = map.get(this);
    return this.typeValue(o);
  }

  /** Returns the value of a file property. */
  public File fileValue(Map<Prop, Object> map) {
    checkType(File.class);
    Object o = map.get(this);
    return this.typeValue(o);
  }

  /** Returns the value of an enum property. */
  public <E extends Enum<E>> E enumValue(Map<Prop, Object> map, Class<E> type) {
    checkType(type);
    Object o = map.get(this);
    return this.typeValue(o);
  }

  @SuppressWarnings("unchecked")
  private <T> T typeValue(Object o) {
    if (o == null) {
      if (defaultValue == null) {
        throw new RuntimeException(
            "no value for property " + camelName + " and no default value");
      }
      return (T) defaultValue;
    }
    return (T) o;
  }

  /** Sets the value of a property, allowing strings for enum types. */
  @SuppressWarnings({"rawtypes", "unchecked"})
  public void setLenient(Map<Prop, Object> map, @Nullable Object value) {
    if (type.isEnum() && value instanceof String) {
      Optional<Enum> optional =
          Enums.getIfPresent(
              (Class<Enum>) type, ((String) value).toUpperCase(Locale.ROOT));
      if (!optional.isPresent()) {
        String values =
            Arrays.stream((Enum[]) type.getEnumConstants())
                .map(Enum::name)
                .collect(Collectors.joining("', '", "'", "'"));
        throw new RuntimeException("value must be one of: " + values);
      }
      set(map, optional.get());
      return;
    }
    set(map, value);
  }

  /** Sets the value of a property. Checks that its type is valid. */
  public void set(Map<Prop, Object> map, @Nullable Object value) {
    if (value == null) {
      if (required) {
        throw new RuntimeException("property is required");
      }
      map.remove(this);
    } else {
      if (!type.isInstance(value)) {
        throw new RuntimeException("value for property must have type " + type);
      }
      map.put(this, value);
    }
  }

  /**
   * Removes the value of this property from a map, returning the previous value
   * or null.
   */
  public Object remove(Map<Prop, Object> map) {
    return map.remove(this);
  }

  /** Allowed values for {@link #OUTPUT} property. */
  public enum Output {
    /** Classic output type, same as Standard ML. The default. */
    CLASSIC,
    /** Tabular output if the value is a list of records, otherwise classic. */
    TABULAR
  }
}

// End Prop.java
