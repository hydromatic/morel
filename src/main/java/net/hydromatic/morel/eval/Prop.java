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
      Kind.STRING,
      JavaVersion.banner(null),
      "Startup banner message displayed when launching the Morel " //
          + "shell."),

  /**
   * String option property "colorScheme" selects the color scheme used for
   * syntax highlighting in the shell.
   *
   * <p>Its value is a built-in scheme ("dark", "light" or "none") or the name
   * of a user-defined scheme. If unset (the default), the scheme is deduced
   * from the environment (see {@code Sys.deduceColorScheme}).
   */
  COLOR_SCHEME(
      "colorScheme",
      Kind.STRING_OPTION,
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
      Kind.FILE,
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
      Kind.STRING,
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
      Kind.BOOL,
      false,
      "Whether to try to create a hybrid execution plan that uses Apache Calcite relational algebra."),

  /** Maximum number of inlining passes. */
  INLINE_PASS_COUNT(
      "inlinePassCount", Kind.INT, 5, "Maximum number of inlining passes."),

  /**
   * Integer option property "lineWidth" controls printing. The length at which
   * lines are wrapped; {@code NONE} means that lines are not wrapped.
   *
   * <p>It is based upon the "linewidth" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library. Default is {@code SOME 79}.
   */
  LINE_WIDTH(
      "lineWidth",
      Kind.NON_NEGATIVE_INT_OPTION,
      79,
      "When printing, the length at which lines are wrapped. Must not be "
          + "negative; NONE means that lines are not wrapped."),

  /**
   * Boolean property "matchCoverageEnabled" controls whether to check the
   * coverage of patterns. If true (the default), Morel warns if patterns are
   * redundant and gives errors if patterns are not exhaustive. If false, Morel
   * does not analyze pattern coverage, and therefore will not give warnings or
   * errors.
   */
  MATCH_COVERAGE_ENABLED(
      "matchCoverageEnabled",
      Kind.BOOL,
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
      Kind.BOOL,
      false,
      "Whether the script-test harness compares output verbatim, rather than "
          + "modulo whitespace and bag-element order."),

  /**
   * Integer option property "maxUseDepth" is how deeply {@code use} may nest. A
   * {@code use} at a greater depth fails as if the file could not be opened,
   * rather than recursing until the Java stack is exhausted.
   *
   * <p>Default is 50. A script that nests {@code use} more deeply than that is
   * almost certainly recursing, directly or indirectly, into a file it is
   * already reading. {@code NONE} means no limit; a depth, if given, must not
   * be negative.
   */
  MAX_USE_DEPTH(
      "maxUseDepth",
      Kind.NON_NEGATIVE_INT_OPTION,
      50,
      "How deeply the 'use' command may nest. Must not be negative; NONE "
          + "means no limit."),

  /**
   * String option property "now" overrides the current time returned by {@code
   * Time.now()} and used by {@code Date.localOffset()}. Value is an ISO-8601
   * instant string (e.g. {@code "2024-01-01T00:00:00Z"}). If not set, the
   * system clock is used.
   */
  NOW(
      "now",
      Kind.STRING_OPTION,
      null,
      "Overrides the current time. Value is an ISO-8601 string (e.g. "
          + "'2024-01-01T00:00:00Z'). If not set, the system clock is used."),

  /**
   * String property "output" controls how values are printed in the shell.
   * Default is "classic".
   */
  OUTPUT(
      "output",
      Kind.OUTPUT_ENUM,
      Output.CLASSIC,
      "How values should be formatted. \"classic\" (the default) prints values in a compact nested format; \"tabular\" prints values in a table if their type is a list of records."),

  /**
   * Integer option property "printDepth" controls printing. The depth of
   * nesting of recursive data structure at which ellipsis begins.
   *
   * <p>It is based upon the "printDepth" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library. Default is {@code SOME 5};
   * {@code NONE} means that values are printed in full.
   */
  PRINT_DEPTH(
      "printDepth",
      Kind.NON_NEGATIVE_INT_OPTION,
      5,
      "When printing, the depth of nesting of recursive data structure at "
          + "which ellipsis begins. Must not be negative; NONE means that "
          + "values are printed in full."),

  /**
   * Integer option property "printLength" controls printing. The length of
   * lists at which ellipsis begins.
   *
   * <p>It is based upon the "printLength" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library.
   *
   * <p>Default is {@code SOME 12}; {@code NONE} means that lists are printed in
   * full.
   */
  PRINT_LENGTH(
      "printLength",
      Kind.NON_NEGATIVE_INT_OPTION,
      12,
      "When printing, the length of lists at which ellipsis begins. Must "
          + "not be negative; NONE means that lists are printed in full."),

  /**
   * String property "productName" is the name of the Morel product.
   *
   * <p>The value is sourced from {@link JavaVersion#MOREL_PRODUCT}. This
   * property is read-only and should not be modified via {@code Sys.set}.
   */
  PRODUCT_NAME(
      "productName",
      Kind.STRING,
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
      Kind.STRING,
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
      Kind.POSITIVE_INT_INF,
      BigInteger.ONE.shiftLeft(24).subtract(BigInteger.ONE),
      "Largest number of values that expanding a range may produce. Must be "
          + "positive."),

  /**
   * Boolean property "relationalize" is whether to convert to relational
   * algebra. Default is false.
   */
  RELATIONALIZE(
      "relationalize",
      Kind.BOOL,
      false,
      "Whether to convert to relational algebra."),

  /**
   * File property "scriptDirectory" is the path of the directory where the
   * {@code use} command looks for scripts. When running a script, it is
   * generally set to the directory that contains the script.
   */
  SCRIPT_DIRECTORY(
      "scriptDirectory",
      Kind.FILE,
      new File(""),
      "Path of the directory where the 'use' command looks for scripts. "
          + "When running a script, it is generally set to the directory that "
          + "contains the script."),

  /**
   * Integer option property "stringDepth" is the length of strings at which
   * ellipsis begins.
   *
   * <p>It is based upon the "stringDepth" property in the <a
   * href="https://www.smlnj.org/doc/Compiler/pages/printcontrol.html">PRINTCONTROL
   * signature</a> of the Standard Basis Library. Default is {@code SOME 70};
   * {@code NONE} means that strings are printed in full.
   */
  STRING_DEPTH(
      "stringDepth",
      Kind.NON_NEGATIVE_INT_OPTION,
      70,
      "When printing, the length of strings at which ellipsis begins. Must "
          + "not be negative; NONE means that strings are printed in "
          + "full."),

  /**
   * Integer option property "stringFold" controls how tabular mode renders long
   * strings. When set, strings longer than this value are folded across
   * multiple lines, breaking at word boundaries when possible. The value must
   * be positive; {@code NONE}, the default, disables folding.
   */
  STRING_FOLD(
      "stringFold",
      Kind.POSITIVE_INT_OPTION,
      null,
      "In tabular mode, the column width at which long strings are folded "
          + "across multiple lines. Must be positive; NONE disables "
          + "folding."),

  /**
   * String option property "terminalBackground" is the terminal's background
   * color, of the form {@code "rgb:RRRR/GGGG/BBBB"} (each channel 1 to 4
   * hexadecimal digits). The shell sets it at startup by querying the terminal;
   * it is used to deduce the color scheme when {@code colorScheme} is unset.
   */
  TERMINAL_BACKGROUND(
      "terminalBackground",
      Kind.STRING_OPTION,
      null,
      "The terminal's background color, of the form 'rgb:RRRR/GGGG/BBBB'. Set "
          + "by the shell at startup; used to deduce the color scheme when "
          + "'colorScheme' is unset."),

  /**
   * String option property "timeZone" overrides the local timezone used by
   * {@code Date.fromTimeLocal()}, {@code Date.localOffset()}, and {@code
   * Date.date} when {@code offset=NONE}. Value is a timezone ID (e.g. {@code
   * "UTC"} or {@code "America/New_York"}). If not set, the JVM default timezone
   * is used.
   */
  TIME_ZONE(
      "timeZone",
      Kind.STRING_OPTION,
      null,
      "Overrides the local timezone. Value is a timezone ID (e.g. 'UTC' or "
          + "'America/New_York'). If not set, the JVM default timezone is used.");

  public final String camelName;
  private final Kind kind;
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
      Kind kind,
      @Nullable Object defaultValue,
      String description) {
    this.camelName = camelName;
    this.kind = kind;
    this.defaultValue = defaultValue;
    this.description = description;
    checkArgument(
        CaseFormat.LOWER_CAMEL
            .to(CaseFormat.UPPER_UNDERSCORE, camelName)
            .equals(name()));
    if (defaultValue == null) {
      // Every property has a value. A property whose default is null is of
      // option type, and its default value is NONE.
      checkArgument(
          kind.option, "property %s must have a default value", camelName);
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
    if (!kind.javaType.isInstance(value)) {
      final @Nullable Object converted = lenient ? convert(value) : null;
      if (converted == null) {
        return false;
      }
      value = converted;
    }
    return kind.checks(value);
  }

  /**
   * Converts a property value to the correct type. Assumes that it is not null
   * and is not the correct type. Returns null if it cannot be converted.
   */
  @SuppressWarnings({"unchecked", "rawtypes"})
  private @Nullable Object convert(Object value) {
    if (kind.javaType.isEnum() && value instanceof String) {
      final String name = ((String) value).toUpperCase(Locale.ROOT);
      return Enums.getIfPresent((Class<Enum>) kind.javaType, name).orNull();
    }
    if (kind.javaType == BigInteger.class) {
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
   * Reads a value of this property's type from a string, as the command line
   * writes it; null if the string is not one.
   *
   * <p>This is a wider conversion than {@link #convert}, which serves {@code
   * Sys.set}. A value from a Morel program already has a type, and a string
   * there is a string; a value from the command line is only ever a string, and
   * every type must be read out of one.
   */
  private @Nullable Object parse(String value) {
    if (kind.javaType == String.class) {
      return value;
    }
    if (kind.javaType == Boolean.class) {
      switch (value) {
        case "true":
          return Boolean.TRUE;
        case "false":
          return Boolean.FALSE;
        default:
          return null;
      }
    }
    if (kind.javaType == File.class) {
      return new File(value);
    }
    if (kind.javaType == Integer.class) {
      try {
        return Integer.valueOf(value);
      } catch (NumberFormatException e) {
        return null;
      }
    }
    // An enum and an IntInf.int are read from a string already.
    return convert(value);
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
    if (kind.javaType.isEnum()) {
      String values =
          Arrays.stream((Enum[]) kind.javaType.getEnumConstants())
              .map(Enum::name)
              .collect(Collectors.joining("', '", "'", "'"));
      return format(
          "value for property '%s' must be one of: %s", camelName, values);
    }
    return mustHaveType();
  }

  /**
   * Returns the message to give for a value outside this property's type. The
   * message gives the type in full, conditions and all, because a value that
   * fails a condition is as far outside the type as one of the wrong class.
   */
  private String mustHaveType() {
    return format(
        "value for property '%s' must have type '%s'",
        camelName, kind.typeString);
  }

  /** Returned by {@link #match} for a value that the property will not take. */
  private static final Object MISMATCH = new Object();

  /** Returned by {@link #match} for {@code NONE}, which is stored as null. */
  private static final Object NONE_VALUE = new Object();

  /**
   * Returns {@code value} as a value of this property's type, or {@link
   * #MISMATCH} if it is not one.
   *
   * <p>For a property of option type, a bare {@code v} means {@code SOME v}, so
   * that {@code Sys.set ("printDepth", 3)} and {@code Sys.set ("printDepth",
   * SOME 3)} mean the same; {@code NONE} gives null; and {@code SOME v} gives
   * {@code v}. A value that is none of those, such as a string where the
   * property takes an {@code int}, is a mismatch, and so is {@code SOME v}
   * where {@code v} is.
   *
   * <p>Morel writes {@code SOME v} as a two-element list, so a list whose head
   * is {@code "SOME"} and whose tail is a value of the property's type reads as
   * an option, whatever the caller meant. Telling the two apart would need the
   * type of the argument, which {@code Sys.set} does not see.
   */
  private Object match(@Nullable Object value) {
    if (value == null) {
      return kind.option ? NONE_VALUE : MISMATCH;
    }
    if (kind.javaType.isInstance(value)) {
      return value;
    }
    if (kind.option && value instanceof List) {
      final List<?> list = (List<?>) value;
      if (list.size() == 1 && "NONE".equals(list.get(0))) {
        return NONE_VALUE;
      }
      if (list.size() == 2 && "SOME".equals(list.get(0))) {
        final Object v = list.get(1);
        return kind.javaType.isInstance(v) ? v : convertOrMismatch(v);
      }
    }
    return convertOrMismatch(value);
  }

  /** Converts a value to this property's type, or returns {@link #MISMATCH}. */
  private Object convertOrMismatch(Object value) {
    final @Nullable Object converted = convert(value);
    return converted == null ? MISMATCH : converted;
  }

  /**
   * Looks up a property by name, returning null if there is no such property.
   */
  public static @Nullable Prop lookup(String propName) {
    return BY_NAME.get(propName);
  }

  /**
   * Returns the value of a property, or null if the property is an option that
   * has been set to {@code NONE}.
   *
   * <p>A property whose value has been removed (by {@code Sys.unset}) reverts
   * to its default value. That is a different thing from a property that has
   * been set to {@code NONE}: {@code maxUseDepth}, for instance, defaults to
   * {@code SOME 50}, and only an explicit {@code NONE} makes it unlimited.
   */
  public @Nullable Object get(Map<Prop, Object> map) {
    return map.containsKey(this) ? map.get(this) : defaultValue;
  }

  /**
   * Returns the value of an integer option property; null if it is {@code
   * NONE}.
   */
  public @Nullable Integer optionalIntValue(Map<Prop, Object> map) {
    checkType(Integer.class);
    return (Integer) get(map);
  }

  /**
   * Returns the value of an integer option property, or {@code ifNone} if it is
   * {@code NONE}.
   */
  public int optionalIntValue(Map<Prop, Object> map, int ifNone) {
    final @Nullable Integer value = optionalIntValue(map);
    return value == null ? ifNone : value;
  }

  /**
   * Returns the value of a property, spelled as a Morel value that {@code
   * Sys.set} would accept: for an option property, {@code NONE} or {@code SOME
   * v}; for any other property, the value alone.
   *
   * @see #typeName()
   */
  public String showValue(Map<Prop, Object> map) {
    final @Nullable Object value = get(map);
    if (!kind.option) {
      return requireNonNull(value, camelName).toString();
    }
    return value == null ? "NONE" : "SOME " + value;
  }

  /** Throws if the requested type does not match this property's type. */
  private void checkType(Class<?> requestedType) {
    checkArgument(
        kind.javaType == requestedType,
        "invalid type %s for property %s",
        kind,
        camelName);
  }

  /** Returns the value of a boolean property. */
  public boolean booleanValue(Map<Prop, Object> map) {
    checkType(Boolean.class);
    Object o = map.get(this);
    return this.<Boolean>typeValue(o);
  }

  /**
   * Returns the value of an integer property.
   *
   * <p>The property must not be of option type. Such a property has no {@code
   * int} value when it is {@code NONE}, and so must be read with {@link
   * #optionalIntValue(Map)} or {@link #optionalIntValue(Map, int)}.
   */
  public int intValue(Map<Prop, Object> map) {
    checkType(Integer.class);
    checkArgument(
        !kind.option,
        "property %s is an option; use optionalIntValue",
        camelName);
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
  public <E extends Enum<E>> E enumValue(
      Map<Prop, Object> map, Class<E> enumType) {
    checkType(enumType);
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
    final Object matched = match(value);
    if (matched == MISMATCH) {
      return invalidValueMessage();
    }
    final @Nullable Object v = matched == NONE_VALUE ? null : matched;
    if (!kind.checks(v)) {
      return invalidValueMessage();
    }
    // NONE is a value in its own right, distinct from the property having no
    // value; see #get(Map).
    map.put(this, v);
    return null;
  }

  /** Sets the value of a property. Checks that its type is valid. */
  public void set(Map<Prop, Object> map, @Nullable Object value) {
    final @Nullable String message = setLenient(map, value);
    if (message != null) {
      throw new RuntimeException(message);
    }
  }

  /**
   * Sets the value of a property from a string, as the command line gives it:
   * for a property of option type, {@code NONE} means no value, and any other
   * string is read as a value of the property's type, so that {@code 50} sets a
   * property of type {@code int option} to {@code SOME 50}.
   *
   * <p>Throws if the property will not take the value, as {@link #set} does.
   * Use {@link #setLenient} where the value comes from a Morel program: there
   * it already has a type, and {@code "NONE"} is the string.
   *
   * <p>A property of type {@code string option} therefore cannot be set to the
   * string {@code "NONE"} from the command line. No such property needs to be.
   */
  public void setFromString(Map<Prop, Object> map, String value) {
    if (kind.option && value.equals("NONE")) {
      // NONE is a value in its own right; "setLenient" reads null as NONE.
      set(map, null);
      return;
    }
    final @Nullable Object parsed = parse(value);
    if (parsed == null) {
      throw new RuntimeException(invalidValueMessage());
    }
    set(map, parsed);
  }

  /**
   * Removes the value of this property from a map, returning the previous value
   * or null.
   */
  public @Nullable Object remove(Map<Prop, Object> map) {
    return map.remove(this);
  }

  /**
   * The type name, in printable form, without any condition the type checks; a
   * property whose type checks one says so in its {@link #description}.
   */
  public String typeName() {
    return kind.typeName;
  }

  /**
   * The default value, in printable form. A property of option type whose
   * default is {@code NONE} prints it as Morel writes it; one whose default is
   * a value prints the value alone, without the {@code SOME}.
   */
  public Object defaultValue() {
    final @Nullable Object value = rawDefaultValue();
    return value == null ? "NONE" : value;
  }

  /**
   * The default value, in printable form, ignoring whether this property is an
   * option.
   */
  private @Nullable Object rawDefaultValue() {
    // Do not switch on 'this'. In JDK 8, javac puts the switch maps of every
    // enum switch in this file into one synthetic class, and so a switch here
    // would make the switch in 'Kind.checks' -- which the constructor reaches,
    // via 'isValid' -- call 'Prop.values()' before 'Prop' is initialized.
    if (this == BANNER) {
      return "Morel version ...";
    }
    if (this == OUTPUT) {
      return requireNonNull((Output) defaultValue)
          .name()
          .toLowerCase(Locale.ROOT);
    }
    return defaultValue;
  }

  /**
   * The type of a property.
   *
   * <p>Every property has a value; a property of option type may have the value
   * {@code NONE}, which each such property reads as an absence of its own sort
   * -- no limit, no folding, no override -- and which is a value to set,
   * distinct from {@code Sys.unset} restoring the default.
   *
   * <p>A property of arbitrary precision has type {@code IntInf.int}, not
   * {@code int}: its value may be larger than a Morel {@code int} can hold, and
   * is then written as a numeral in a string. {@code IntInf} is the name the
   * Standard Basis gives such integers, though Morel has no such structure yet.
   */
  private enum Kind {
    // lint: sort until '##Kind' where '##[A-Z]'
    BOOL("bool", Boolean.class),
    FILE("file", File.class),
    INT("int", Integer.class),
    INT_OPTION("int option", Integer.class),
    NON_NEGATIVE_INT_OPTION(
        "(int check i => i >= 0) option", "int option", Integer.class),
    OUTPUT_ENUM("enum", Output.class),
    POSITIVE_INT_INF(
        "IntInf.int check i => i > 0", "IntInf.int", BigInteger.class),
    POSITIVE_INT_OPTION(
        "(int check i => i > 0) option", "int option", Integer.class),
    STRING("string", String.class),
    STRING_OPTION("string option", String.class);

    /** The type, as Morel writes it, including any condition it checks. */
    final String typeString;

    /**
     * The type without its conditions: the name that a table of properties
     * gives, where the conditions are prose.
     */
    final String typeName;

    /**
     * The Java class of the property's value; of the value inside the option,
     * if {@link #option}.
     */
    final Class<?> javaType;

    /** Whether the type is an option, and so admits {@code NONE}. */
    final boolean option;

    Kind(String typeName, Class<?> javaType) {
      this(typeName, typeName, javaType);
    }

    Kind(String typeString, String typeName, Class<?> javaType) {
      this.typeString = requireNonNull(typeString, "typeString");
      this.typeName = requireNonNull(typeName, "typeName");
      this.javaType = requireNonNull(javaType, "javaType");
      this.option = typeName.endsWith(" option");
      checkArgument(
          javaType == BigInteger.class
              || javaType == Boolean.class
              || javaType == File.class
              || javaType == Integer.class
              || javaType == String.class
              || javaType.isEnum(),
          "not a property type: %s",
          javaType);
    }

    /**
     * Returns whether a value -- null for {@code NONE} -- satisfies the
     * condition that this type checks.
     *
     * <p>A type that checks nothing admits every value of its Java class, and
     * most do. The rest are listed here, so that a condition is stated once, in
     * the type, however many properties have that type.
     */
    boolean checks(@Nullable Object value) {
      switch (this) {
        case NON_NEGATIVE_INT_OPTION:
          return value == null || (Integer) value >= 0;
        case POSITIVE_INT_INF:
          // Not an option type, so the value is never NONE.
          return ((BigInteger) requireNonNull(value)).signum() > 0;
        case POSITIVE_INT_OPTION:
          return value == null || (Integer) value > 0;
        default:
          if (this.typeString.contains(" check ")) {
            throw new AssertionError("'check' requires 'case'");
          }
          return value != null || option;
      }
    }

    @Override
    public String toString() {
      return typeString;
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
