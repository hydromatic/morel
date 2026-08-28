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
import static java.lang.String.format;
import static java.util.Objects.requireNonNull;

import com.google.common.base.CaseFormat;
import com.google.common.base.Enums;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Ordering;
import java.io.File;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import net.hydromatic.morel.util.JavaVersion;
import org.jspecify.annotations.Nullable;

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
   * String property "colorScheme" selects the color scheme used for syntax
   * highlighting in the shell.
   *
   * <p>Its value is a built-in scheme ("dark", "light" or "none") or the name
   * of a user-defined scheme. If unset (the default), the scheme is deduced
   * from the environment (see {@code Sys.deduceColorScheme}).
   */
  COLOR_SCHEME(
      "colorScheme",
      String.class,
      false,
      null,
      "Color scheme for syntax highlighting in the shell: a built-in scheme "
          + "('dark', 'light' or 'none'), or a user-defined scheme. If unset, "
          + "the scheme is deduced from the environment."),

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

  /**
   * Boolean property "matchStrict" controls how the script-test harness
   * compares actual output against the expected output in a {@code .smli}
   * script. If false (the default), output matches if it is equivalent modulo
   * whitespace, line breaks and the order of bag elements. If true, output must
   * match character-for-character; this is useful for testing pretty-printing.
   */
  MATCH_STRICT(
      "matchStrict",
      Boolean.class,
      true,
      false,
      "Whether the script-test harness compares output verbatim, rather than "
          + "modulo whitespace and bag-element order."),

  /**
   * String property "now" overrides the current time returned by {@code
   * Time.now()} and used by {@code Date.localOffset()}. Value is an ISO-8601
   * instant string (e.g. {@code "2024-01-01T00:00:00Z"}). If not set, the
   * system clock is used.
   */
  NOW(
      "now",
      String.class,
      false,
      null,
      "Overrides the current time. Value is an ISO-8601 string (e.g. "
          + "'2024-01-01T00:00:00Z'). If not set, the system clock is used."),

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
   * Integer property "rangeMaxLength" is the largest number of values that
   * expanding a range may produce.
   *
   * <p>A discrete domain is finite but not therefore small: "int" alone has
   * 2^32 values, and a nine-character word 2^72. An unremarkable range can thus
   * ask for more values than will fit in memory, and past this many {@code
   * Size} is raised instead.
   *
   * <p>Default is 2^24 - 1, the same as {@code Vector.maxLen}.
   *
   * <p>The value may be larger than a Morel "int" can hold, so it is written as
   * a string where it does not fit: {@code Sys.set ("rangeMaxLength",
   * "4722366482869645213696")}.
   */
  RANGE_MAX_LENGTH(
      "rangeMaxLength",
      BigInteger.class,
      true,
      BigInteger.ONE.shiftLeft(24).subtract(BigInteger.ONE),
      "Largest number of values that expanding a range may produce."),

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
      "When printing, the length of strings at which ellipsis begins."),

  /**
   * Integer property "stringFold" controls how tabular mode renders long
   * strings. When set, strings longer than this value are folded across
   * multiple lines, breaking at word boundaries when possible. Legal values are
   * 1 or greater. If not set (the default), folding is disabled.
   */
  STRING_FOLD(
      "stringFold",
      Integer.class,
      false,
      null,
      "In tabular mode, the column width at which long strings are folded "
          + "across multiple lines. If not set, folding is disabled. "
          + "Legal values are 1 or greater."),

  /**
   * String property "terminalBackground" is the terminal's background color, of
   * the form {@code "rgb:RRRR/GGGG/BBBB"} (each channel 1 to 4 hexadecimal
   * digits). The shell sets it at startup by querying the terminal; it is used
   * to deduce the color scheme when {@code colorScheme} is unset.
   */
  TERMINAL_BACKGROUND(
      "terminalBackground",
      String.class,
      false,
      null,
      "The terminal's background color, of the form 'rgb:RRRR/GGGG/BBBB'. Set "
          + "by the shell at startup; used to deduce the color scheme when "
          + "'colorScheme' is unset."),

  /**
   * String property "timeZone" overrides the local timezone used by {@code
   * Date.fromTimeLocal()}, {@code Date.localOffset()}, and {@code Date.date}
   * when {@code offset=NONE}. Value is a timezone ID (e.g. {@code "UTC"} or
   * {@code "America/New_York"}). If not set, the JVM default timezone is used.
   */
  TIME_ZONE(
      "timeZone",
      String.class,
      false,
      null,
      "Overrides the local timezone. Value is a timezone ID (e.g. 'UTC' or "
          + "'America/New_York'). If not set, the JVM default timezone is used.");

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
      checkArgument(isValid(defaultValue));
    }
  }

  /** Returns whether a given value is valid for this property. */
  public boolean isValid(Object value) {
    return isValid(value, false);
  }

  /**
   * Returns whether a given value is valid for this property, allowing
   * conversions if {@code lenient}.
   */
  public boolean isValid(Object value, boolean lenient) {
    return isPropertyType(type)
        && (type.isInstance(value) || lenient && convert(value) != null);
  }

  /** Returns whether a property may have a given type. */
  private static boolean isPropertyType(Class<?> type) {
    return type == BigInteger.class
        || type == Boolean.class
        || type == File.class
        || type == Integer.class
        || type == String.class
        || type.isEnum();
  }

  /**
   * Converts a property value to the correct type. Assumes that it is not null
   * and is not the correct type. Returns null if it cannot be converted.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private @Nullable Object convert(Object value) {
    if (type.isEnum() && value instanceof String) {
      final String name = ((String) value).toUpperCase(Locale.ROOT);
      return Enums.getIfPresent((Class<Enum>) type, name).orNull();
    }
    if (type == BigInteger.class) {
      if (value instanceof Integer) {
        return BigInteger.valueOf((Integer) value);
      }
      if (value instanceof String) {
        try {
          return new BigInteger((String) value);
        } catch (NumberFormatException e) {
          return null;
        }
      }
    }
    return null;
  }

  /**
   * Returns the message to give for a value this property cannot take.
   *
   * <p>The message names the property, and describes what it will take in
   * Morel's terms: the name of a Morel type, or, for an enumerated property,
   * the values themselves. It does not name the value it rejected, nor any Java
   * type.
   */
  private String invalidValueMessage() {
    if (type.isEnum()) {
      String values =
          Arrays.stream((Enum[]) type.getEnumConstants())
              .map(Enum::name)
              .collect(Collectors.joining("', '", "'", "'"));
      return format(
          "value for property '%s' must be one of: %s", camelName, values);
    }
    return format(
        "value for property '%s' must have type '%s'", camelName, typeName());
  }

  /**
   * Looks up a property by name, returning null if there is no such property.
   */
  public static @Nullable Prop lookup(String propName) {
    return BY_NAME.get(propName);
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

  /** Returns the value of a {@link BigInteger} property. */
  public BigInteger bigIntegerValue(Map<Prop, Object> map) {
    checkType(BigInteger.class);
    Object o = map.get(this);
    return this.typeValue(o);
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

  /**
   * Sets the value of a property, allowing strings for enum types, and returns
   * null; or, if the property will not take the value, leaves the property
   * unchanged and returns the message saying why.
   *
   * <p>It returns the message rather than throwing because its caller is {@code
   * Sys.set}, which raises a Morel {@code Fail} exception at the call site. Use
   * {@link #set} where the value comes from the command line rather than from a
   * Morel program, and a value the property will not take is a bug.
   */
  public @Nullable String setLenient(
      Map<Prop, Object> map, @Nullable Object value) {
    if (value == null) {
      if (required) {
        return "property is required";
      }
      map.remove(this);
      return null;
    }
    if (!type.isInstance(value)) {
      final @Nullable Object converted = convert(value);
      if (converted == null) {
        return invalidValueMessage();
      }
      value = converted;
    }
    map.put(this, value);
    return null;
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
        throw new RuntimeException(invalidValueMessage());
      }
      map.put(this, value);
    }
  }

  /**
   * Removes the value of this property from a map, returning the previous value
   * or null.
   */
  public @Nullable Object remove(Map<Prop, Object> map) {
    return map.remove(this);
  }

  /** The type name, in printable form. */
  public String typeName() {
    if (type.isEnum()) {
      return "enum";
    } else if (type == BigInteger.class) {
      // Not "int"; the value may be larger than a Morel "int" can hold, and is
      // then written as a numeral in a string. "IntInf.int" is the name the
      // Standard Basis gives arbitrary-precision integers, though Morel has
      // no such structure yet.
      return "IntInf.int";
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
