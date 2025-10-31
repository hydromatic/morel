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
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import net.hydromatic.morel.ast.Ast;
import net.hydromatic.morel.ast.AstNode;
import net.hydromatic.morel.ast.Op;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.eval.Codes;
import net.hydromatic.morel.type.DataType;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ForallType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.TupleType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.type.TypeVar;

/** Validates signature declarations against enum values in {@link BuiltIn}. */
class SignatureChecker {
  private final TypeSystem typeSystem;

  SignatureChecker() {
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
                final AstNode node = parser.statementSemicolonOrEof();
                assertThat(node, notNullValue());
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
   * Converts signature name to structure. For example, signature "INTEGER" maps
   * to structure "Int".
   */
  private static String structureName(Ast.SignatureBind bind) {
    switch (bind.name.name) {
      case "BOOLEAN":
        return "Bool";
      case "INTEGER":
        return "Int";
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
      if (spec.op == Op.SPEC_VAL) {
        final Ast.ValSpec valSpec = (Ast.ValSpec) spec;
        String signatureName = valSpec.name.name;
        // Normalize operator names by adding "op " prefix for lookup
        String lookupName = alphaName(signatureName);
        // Skip datatype constructors for List (they're handled by PSEUDO_LIST)
        if ("List".equals(structure)
            && ("nil".equals(lookupName) || "op ::".equals(lookupName))) {
          continue;
        }
        map2.put(lookupName, str(structure, valSpec));
      } else if (spec.op == Op.SPEC_EXCEPTION) {
        // Verify exception declarations
        final Ast.ExceptionSpec exnSpec = (Ast.ExceptionSpec) spec;
        verifyException(structure, exnSpec);
      } else if (spec.op == Op.SPEC_DATATYPE) {
        // Verify datatype constructors
        final Ast.DatatypeSpec datatypeSpec = (Ast.DatatypeSpec) spec;
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
        // lint: sort until '#}' where '##case '
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
          && exnName.equals(builtInExn.mlName)) {
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
      // Normalize constructor name for lookup
      String normalizedName = normalizeConstructorName(constructorName);

      // Find matching constructor in BuiltIn.Constructor
      BuiltIn.Constructor foundConstructor = null;
      for (BuiltIn.Constructor constructor : BuiltIn.Constructor.values()) {
        if (constructor.constructor.equals(normalizedName)) {
          foundConstructor = constructor;
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
          return format("ts.list(%s)", str(listType.elementType));
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
