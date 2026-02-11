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
import static java.util.Objects.requireNonNull;

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
import net.hydromatic.morel.util.JavaVersion;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Property.
 *
 * @see Session#map
 */
public enum Prop {
  // lint: sort until '##public ' where '##[A-Z]'

  /**
   * String property "banner" is the startup banner message displayed when
   * launching the Morel shell.
   *
   * <p>The format matches the output of {@code Shell.banner()}. This property
   * is read-only and should not be modified via {@code Sys.set}.
   */
  BANNER(
      "banner",
      String.class,
      true,
      JavaVersion.banner(null),
      "Startup banner message displayed when launching the Morel " //
          + "shell."),

  /**
   * File property "directory" is the path of the directory that the {@code
   * file} variable maps to in this connection.
   *
   * <p>The default value is the empty string; many tests use the
   * "src/test/resources" directory; when launched via the {@code morel} shell
   * script, the default value is the shell's current directory.
   */
  DIRECTORY(
      "directory",
      File.class,
      true,
      new File(""),
      "Path of the directory that the 'file' variable maps to in " //
          + "this connection."),

  /**
   * String property "excludeStructures" is a Java regular expression that
   * controls which built-in structures are excluded from the environment. A
   * structure whose name matches the regex is excluded.
   *
   * <p>Default is "^Test$", which excludes the {@code Test} structure.
   */
  EXCLUDE_STRUCTURES(
      "excludeStructures",
      String.class,
      true,
      "^Test$",
      "Regular expression that controls which built-in structures are excluded "
          + "from the environment."),

  /**
   * Boolean property "hybrid" controls whether to try to create a hybrid
   * execution plan that uses Apache Calcite relational algebra wherever
   * possible. Default is false.
   */
  HYBRID(
      "hybrid",
      Boolean.class,
      true,
      false,
      "Whether to try to create a hybrid execution plan that uses Apache Calcite relational algebra."),

  /** Maximum number of inlining passes. */
  INLINE_PASS_COUNT(
      "inlinePassCount",
      Integer.class,
      true,
      5,
      "Maximum number of inlining passes."),

  /**
   * Integer property "lineWidth" controls printing. The length at which lines
   * are wrapped.
   *
   * <p>It is based upon the "linewidth" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library. Default is 79.
   */
  LINE_WIDTH(
      "lineWidth",
      Integer.class,
      true,
      79,
      "When printing, the length at which lines are wrapped."),

  /**
   * Boolean property "matchCoverageEnabled" controls whether to check the
   * coverage of patterns. If true (the default), Morel warns if patterns are
   * redundant and gives errors if patterns are not exhaustive. If false, Morel
   * does not analyze pattern coverage, and therefore will not give warnings or
   * errors.
   */
  MATCH_COVERAGE_ENABLED(
      "matchCoverageEnabled",
      Boolean.class,
      true,
      true,
      "Whether to check whether patterns are exhaustive and/or redundant."),

  /** Integer property "optionalInt" is for testing. Default is null. */
  OPTIONAL_INT("optionalInt", Integer.class, false, null, "For testing."),

  /**
   * String property "output" controls how values are printed in the shell.
   * Default is "classic".
   */
  OUTPUT(
      "output",
      Output.class,
      true,
      Output.CLASSIC,
      "How values should be formatted. \"classic\" (the default) prints values in a compact nested format; \"tabular\" prints values in a table if their type is a list of records."),

  /**
   * Integer property "printDepth" controls printing. The depth of nesting of
   * recursive data structure at which ellipsis begins.
   *
   * <p>It is based upon the "printDepth" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library. Default is 5.
   */
  PRINT_DEPTH(
      "printDepth",
      Integer.class,
      true,
      5,
      "When printing, the depth of nesting of recursive data structure at which ellipsis begins."),

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
  PRINT_LENGTH(
      "printLength",
      Integer.class,
      true,
      12,
      "When printing, the length of lists at which ellipsis begins."),

  /**
   * String property "productName" is the name of the Morel product.
   *
   * <p>The value is sourced from {@link JavaVersion#MOREL_PRODUCT}. This
   * property is read-only and should not be modified via {@code Sys.set}.
   */
  PRODUCT_NAME(
      "productName",
      String.class,
      true,
      JavaVersion.MOREL_PRODUCT,
      "Name of the Morel product."),

  /**
   * String property "productVersion" is the current version of Morel.
   *
   * <p>The value is sourced from {@link JavaVersion#MOREL_VERSION}. This
   * property is read-only and should not be modified via {@code Sys.set}.
   */
  PRODUCT_VERSION(
      "productVersion",
      String.class,
      true,
      JavaVersion.MOREL_VERSION.toString(),
      "Current version of Morel."),

  /**
   * Boolean property "relationalize" is whether to convert to relational
   * algebra. Default is false.
   */
  RELATIONALIZE(
      "relationalize",
      Boolean.class,
      true,
      false,
      "Whether to convert to relational algebra."),

  /**
   * File property "scriptDirectory" is the path of the directory where the
   * {@code use} command looks for scripts. When running a script, it is
   * generally set to the directory that contains the script.
   */
  SCRIPT_DIRECTORY(
      "scriptDirectory",
      File.class,
      true,
      new File(""),
      "Path of the directory where the 'use' command looks for scripts. "
          + "When running a script, it is generally set to the directory that "
          + "contains the script."),

  /**
   * Integer property "stringDepth" is the length of strings at which ellipsis
   * begins.
   *
   * <p>It is based upon the "stringDepth" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library. Default is 70.
   */
  STRING_DEPTH(
      "stringDepth",
      Integer.class,
      true,
      70,
      "When printing, the length of strings at which ellipsis begins.");

  public final String camelName;
  public final Class<?> type;
  private final boolean required;
  private final @Nullable Object defaultValue;
  public final String description;

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
        Ordering.from(
            Comparator.comparing((Prop o) -> requireNonNull(o).camelName));
    BY_CAMEL_NAME = ordering.sortedCopy(list);

    final Map<String, Prop> map = new LinkedHashMap<>();
    for (Prop value : BY_CAMEL_NAME) {
      map.put(value.name(), value);
      map.put(value.camelName, value);
    }
    BY_NAME = ImmutableMap.copyOf(map);
  }

  Prop(
      String camelName,
      Class<?> type,
      boolean required,
      @Nullable Object defaultValue,
      String description) {
    this.camelName = camelName;
    this.type = type;
    this.required = required;
    this.defaultValue = defaultValue;
    this.description = description;
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
  public @Nullable Object get(Map<Prop, Object> map) {
    @Nullable Object o = map.get(this);
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
  private <T> T typeValue(@Nullable Object o) {
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

  /** The type name, in printable form. */
  public String typeName() {
    if (type.isEnum()) {
      return "enum";
    } else if (type == Integer.class) {
      return "int";
    } else if (type == String.class) {
      return "string";
    } else if (type == File.class) {
      return "file";
    } else if (type == Boolean.class) {
      return "bool";
    } else {
      throw new IllegalArgumentException(type.getTypeName());
    }
  }

  /** The default value, in printable form. */
  public @Nullable Object defaultValue() {
    switch (this) {
      case BANNER:
        return "Morel version ...";
      case OUTPUT:
        return requireNonNull((Output) defaultValue)
            .name()
            .toLowerCase(Locale.ROOT);
      default:
        return defaultValue;
    }
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
