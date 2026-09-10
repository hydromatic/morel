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

import static net.hydromatic.morel.compile.Resolver.subsumes;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasToString;

import com.google.common.collect.ImmutableSortedMap;
import java.util.ArrayList;
import java.util.Map;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.compile.OutputMatcher;
import net.hydromatic.morel.type.FnType;
import net.hydromatic.morel.type.ListType;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import net.hydromatic.morel.util.PairList;
import org.junit.jupiter.api.Test;

/** Tests for types and the type system. */
public class TypeTest {
  @Test
  void testUnify() {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    final Type intT = PrimitiveType.INT;
    final Type boolT = PrimitiveType.BOOL;
    final Type strT = PrimitiveType.STRING;
    final Type alpha = typeSystem.typeVariable(0);
    final Type iList = typeSystem.listType(intT);
    final Type aList = typeSystem.listType(alpha);
    final Type ibTuple = typeSystem.tupleType(intT, boolT);
    final Type iaTuple = typeSystem.tupleType(intT, alpha);
    final Type i2Tuple = typeSystem.tupleType(intT, intT);
    final Type i3Tuple = typeSystem.tupleType(intT, intT, intT);
    final Type ibTupleList = typeSystem.listType(ibTuple);
    final Type iOption = typeSystem.option(intT);
    final Type aOption = typeSystem.option(alpha);
    final Type biRec =
        typeSystem.recordType(ImmutableSortedMap.of("i", intT, "b", boolT));
    final Type isRec =
        typeSystem.recordType(ImmutableSortedMap.of("i", intT, "s", strT));
    assertEq("int", intT);
    assertCannotUnify("int # bool", intT, boolT);
    assertLt("int < 'a", intT, alpha);
    assertCannotUnify("int # int list", intT, iList);
    assertLt("int list < 'a list", iList, aList);
    assertCannotUnify("int # int option", intT, iOption);
    assertLt("int option < 'a option", iOption, aOption);
    assertCannotUnify("'a option # int list", aOption, iList);
    assertEq("'a option", aOption);
    assertEq("int option", iOption);
    assertEq("(int, bool)", ibTuple);
    assertLt("(int, bool) < (int, 'a)", ibTuple, iaTuple);
    assertLt("(int, 'a) !< (int, bool)", ibTuple, iaTuple);
    assertCannotUnify("(int, int) # (int, int, int)", i2Tuple, i3Tuple);
    assertLt("(int, bool) list < 'a list", ibTupleList, aList);
    assertEq("{b, i}", biRec);
    assertCannotUnify("{b, i} # {b, s}", biRec, isRec);

    // In the following descriptions, "." means "can be called with argument"
    // and "!." means "cannot be called with argument".
    final FnType intToInt = typeSystem.fnType(intT, intT);
    final FnType aToInt = typeSystem.fnType(alpha, intT);
    final FnType intToA = typeSystem.fnType(intT, alpha);
    final FnType i2ToB = typeSystem.fnType(i2Tuple, boolT);
    final FnType i3ToB = typeSystem.fnType(i3Tuple, boolT);
    final FnType recToB = typeSystem.fnType(biRec, boolT);
    assertCannotCall("int !. int", intT, intT);
    assertCanCall("int -> int . int", intToInt, intT);
    assertCannotCall("int -> int . bool", intToInt, boolT);
    assertCanCall("'a -> int . bool", aToInt, boolT);
    assertCanCall("'a -> int . 'a", aToInt, alpha);
    assertCanCall("'a -> int . int list", aToInt, iList);
    // Yes, because the alphas are different
    assertCanCall("'a -> int . 'a list", aToInt, aList);
    assertCanCall("int -> 'a . int", intToA, intT);
    assertCannotCall("(int, int) -> bool !. int", intToA, i2ToB);
    assertCannotCall("(int, int, int) -> bool !. int", intToA, i3ToB);
    assertCanCall("(int, int) -> bool . (int, int)", i2ToB, i2Tuple);
    assertCannotCall("(int, int) -> bool !. (int, int, int)", i2ToB, i3Tuple);
    assertCannotCall("(int, int, int) -> bool !. (int, int)", i3ToB, i2Tuple);
    assertCanCall("(int, int, int) -> bool . (int, int, int)", i3ToB, i3Tuple);
    assertCanCall("{b, i} -> bool . {b, i}", recToB, biRec);
    assertCannotCall("{b, i} -> bool . int", recToB, intT);
    assertCannotCall("{b, i} -> bool . bool", recToB, boolT);
  }

  private static void assertEq(String message, Type type) {
    assertThat(message, type.specializes(type), is(true));
    assertThat(message, type.unifyWith(type), notNullValue());
  }

  /**
   * Asserts that {@code type1} is strictly less general than {@code type2},
   * e.g. "int &lt; &alpha;", "int list &lt; &alpha; list".
   */
  private static void assertLt(String message, Type type1, Type type2) {
    assertThat(message, type1.specializes(type2), is(true));
    assertThat(message, type1.unifyWith(type2), notNullValue());
    assertThat(message, type2.specializes(type1), is(false));
  }

  /**
   * Asserts that type1 cannot be unified with type2. E.g. "int list" cannot be
   * unified with "bool option".
   */
  private static void assertCannotUnify(
      String message, Type type1, Type type2) {
    assertThat(message, type1.unifyWith(type2), nullValue());
    assertThat(message, type2.unifyWith(type1), nullValue());
    assertThat(message, type1.specializes(type2), is(false));
    assertThat(message, type2.specializes(type1), is(false));
  }

  private static void assertCanCall(String reason, Type type, Type argType) {
    assertThat(reason, type.canCallArgOf(argType), is(true));
  }

  private static void assertCannotCall(String reason, Type type, Type argType) {
    assertThat(reason, type.canCallArgOf(argType), is(false));
  }

  @Test
  void testSubsumes() {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());

    final Type intT = PrimitiveType.INT;
    final Type boolT = PrimitiveType.BOOL;
    final Type iRec = typeSystem.recordType(PairList.of("i", intT));
    final Type ibRec =
        typeSystem.recordType(PairList.copyOf("i", intT, "b", boolT));
    final Type jbRec =
        typeSystem.recordType(PairList.copyOf("j", intT, "b", boolT));
    final Type ibxRec =
        typeSystem.recordType(
            PairList.copyOf("i", intT, "b", boolT, "x", intT));
    final ListType ibList = typeSystem.listType(ibRec);
    final Type ibBag = typeSystem.bagType(ibRec);
    final PairList<String, Type> types =
        PairList.copyOf(
            "intT", intT,
            "boolT", boolT,
            "iRec", iRec,
            "ibRec", ibRec,
            "jbRec", jbRec,
            "ibxRec", ibxRec,
            "ibList", ibList,
            "ibBag", ibBag);
    types.forEach(
        (name1, t1) ->
            types.forEach((name2, t2) -> checkSubsumes(name1, t1, name2, t2)));
  }

  private static void checkSubsumes(
      String name1, Type t1, String name2, Type t2) {
    if (t1 == t2) {
      assertThat(name1 + " == " + name2, subsumes(t1, t2), is(true));
    } else {
      assertThat(name1 + " != " + name2, subsumes(t1, t2), is(false));
    }
  }

  @Test
  void testRecordTypeMap() {
    final Map<String, Integer> map1 = RecordType.map("a", 1);
    assertThat(map1, hasToString("{a=1}"));

    final Map<String, Integer> map2 = RecordType.map("b", 2, "a", 1);
    assertThat(map2, hasToString("{a=1, b=2}"));

    // Numeric field names sort in numeric order
    final Map<String, Integer> mapNumeric =
        RecordType.map(
            "1", 1, "2", 2, "3", 3, "4", 4, "5", 5, "6", 6, "7", 7, "8", 8, "9",
            9, "10", 10, "11", 11, "12", 12);
    assertThat(
        mapNumeric,
        hasToString(
            "{1=1, 2=2, 3=3, 4=4, 5=5, 6=6, 7=7, 8=8, 9=9, 10=10, 11=11, 12=12}"));

    // Numeric field names sort before alpha field names. Field names that start
    // with "0" are not regarded as numeric.
    final Map<String, Integer> mapAlphaNumeric =
        RecordType.map(
            "0", 0, "1", 1, "2", 2, "a", 3, "A", 4, "aa", 5, "AAA", 6, "z", 7,
            "00", 8, "002", 9);
    assertThat(
        mapAlphaNumeric,
        hasToString(
            "{1=1, 2=2, 0=0, 00=8, 002=9, A=4, AAA=6, a=3, aa=5, z=7}"));
  }

  /** Tests {@link OutputMatcher}. */
  @Test
  void testOutputMatcher() {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());

    final OutputMatcher m = new OutputMatcher(typeSystem);
    final PrimitiveType intType = PrimitiveType.INT;
    assertThat(m.codeEqual(intType, "1", " 1 "), is(true));
    assertThat(m.codeEqual(intType, "1", " 2 "), is(false));
    final PrimitiveType stringType = PrimitiveType.STRING;
    assertThat(m.codeEqual(stringType, "\"x\"", " \"x\" "), is(true));
    assertThat(m.codeEqual(stringType, "\"\"", " \" \" "), is(false));
    final ListType intListType = typeSystem.listType(intType);
    assertThat(m.codeEqual(intListType, "[1,2,3]", "[ 1,  2,  3] "), is(true));
    final Type stringOptionType = typeSystem.option(stringType);
    assertThat(
        m.codeEqual(stringOptionType, "SOME \"x\"", " SOME  \"x\""), is(true));
    assertThat(
        m.codeEqual(stringOptionType, "SOME \"x\"", " SOME  \"y\""), is(false));
    assertThat(
        m.codeEqual(stringOptionType, "NONE", " SOME  \"x\""), is(false));
    assertThat(m.codeEqual(stringOptionType, "NONE", " NONE "), is(true));

    // A raw string literal is equivalent to the regular literal with the
    // same content; the content is verbatim, so escapes are not processed.
    final String ab = lines("{|a", "b|}");
    assertThat(m.codeEqual(stringType, "\"a\\nb\"", ab), is(true));
    assertThat(
        m.codeEqual(stringType, "\"a\\nb\"", lines("{x|a", "b|x}")), is(true));
    assertThat(m.codeEqual(stringType, ab, ab), is(true));
    assertThat(m.codeEqual(stringType, "\"a\\nc\"", ab), is(false));
    assertThat(m.codeEqual(stringType, "\"a\\\\nb\"", "{|a\\nb|}"), is(true));
    assertThat(m.codeEqual(stringType, "\"a\\nb\"", "{|a\\nb|}"), is(false));
    assertThat(
        m.codeEqual(
            stringType, "\"say \\\"hi\\\"\\n\"", lines("{|say \"hi\"", "|}")),
        is(true));
    assertThat(
        m.codeEqual(stringType, "\"a|}\\nb\"", lines("{q|a|}", "b|q}")),
        is(true));
    assertThat(m.codeEqual(stringType, "\"1\"", "1"), is(false));
    assertThat(m.codeEqual(stringType, "{|1|}", "1"), is(false));
    // An unterminated fence, even in the prefix, gives "not equivalent"
    // rather than an exception
    assertThat(m.codeEqual(stringType, "{a|x", "\"x\""), is(false));
    assertThat(
        m.equivalent(
            stringType,
            "{a| val it = \"x\" : string",
            "{a| val it = \"x\" : string"),
        is(false));
    // If the tag starts with "_", a newline right after the opening fence
    // is not content; otherwise it is
    assertThat(
        m.codeEqual(stringType, "\"a\\nb\"", lines("{_|", "a", "b|_}")),
        is(true));
    assertThat(
        m.codeEqual(stringType, "\"a\\nb\"", lines("{_x|", "a", "b|_x}")),
        is(true));
    assertThat(
        m.codeEqual(stringType, "\"\\na\\nb\"", lines("{|", "a", "b|}")),
        is(true));
    assertThat(
        m.codeEqual(stringType, "\"a\\nb\"", lines("{|", "a", "b|}")),
        is(false));
    assertThat(
        m.codeEqual(stringType, "\"\\na\"", lines("{_|", "", "a|_}")),
        is(true));
    // A tag consists of lower-case letters and underscores; anything else
    // is not a fence.
    assertThat(m.codeEqual(stringType, "\"x\"", "{a_b|x|a_b}"), is(true));
    assertThat(m.codeEqual(stringType, "\"x\"", "{A|x|A}"), is(false));
    assertThat(m.codeEqual(stringType, "\"x\"", "{a1|x|a1}"), is(false));
    assertThat(m.codeEqual(stringType, "\"x\"", "{a-b|x|a-b}"), is(false));
    // A fence inside a regular literal is just text, and a raw literal may
    // contain a fence with a different tag.
    assertThat(
        m.codeEqual(stringType, "\"{ab|x|ab}\"", "{|{ab|x|ab}|}"), is(true));
    assertThat(m.codeEqual(stringType, "\"{|x|}\"", "{a|{|x|}|a}"), is(true));
    assertThat(
        m.codeEqual(stringType, "\"{ab|x|ab}\"", "\"{ab|x|ab}\""), is(true));
    assertThat(
        m.codeEqual(
            typeSystem.listType(stringType),
            "[\"{|a|}\", \"b\"]",
            "[ \"{|a|}\",  \"b\" ]"),
        is(true));
    assertThat(
        m.equivalent(
            stringType,
            "val it = \"{ab| : |ab}\" : string",
            "val it = \"{ab| : |ab}\" : string"),
        is(true));
    // Whole lines, including the type suffix
    assertThat(
        m.equivalent(
            stringType,
            "val it = \"a : b\\nc\" : string",
            lines("val it = {|a : b", "c|} : string")),
        is(true));
    assertThat(
        m.equivalent(
            stringType,
            "val it = \"a\\nc\" : string",
            lines("val it = {|a", "b|} : string")),
        is(false));
    assertThat(
        m.equivalent(
            stringType,
            "val it = \"a\\nb\" : string",
            lines("val x = {|a", "b|} : string")),
        is(false));
    final ListType stringListType = typeSystem.listType(stringType);
    assertThat(
        m.codeEqual(
            stringListType, "[\"a\\nb\", \"c\"]", lines("[{|a", "b|}, \"c\"]")),
        is(true));
  }

  /** Joins lines with newlines (no trailing newline). */
  private static String lines(String... lines) {
    return String.join("\n", lines);
  }

  /** Tests {@link OutputMatcher#toRawStrings}. */
  @Test
  void testToRawStrings() {
    // Unchanged: no newline in the string
    assertThat(
        OutputMatcher.toRawStrings("val it = \"ab\" : string"),
        is("val it = \"ab\" : string"));
    assertThat(
        OutputMatcher.toRawStrings("val it = \"a\\\\nb\" : string"),
        is("val it = \"a\\\\nb\" : string"));
    // Unchanged: a space or tab before a newline
    assertThat(
        OutputMatcher.toRawStrings("val it = \"a \\nb\" : string"),
        is("val it = \"a \\nb\" : string"));
    assertThat(
        OutputMatcher.toRawStrings("val it = \"a\\t\\nb\" : string"),
        is("val it = \"a\\t\\nb\" : string"));
    // A space at the very end is visible (the fence follows it)
    assertThat(
        OutputMatcher.toRawStrings("val it = \"a\\nb \" : string"),
        is(lines("val it = {|a", "b |} : string")));
    // Unchanged: not a top-level string
    assertThat(
        OutputMatcher.toRawStrings("val it = [\"a\\nb\"] : string list"),
        is("val it = [\"a\\nb\"] : string list"));
    assertThat(
        OutputMatcher.toRawStrings("val it = \"a\\nb\" : string variant"),
        is("val it = \"a\\nb\" : string variant"));
    // A newline makes a raw literal; quotes and backslashes become verbatim
    assertThat(
        OutputMatcher.toRawStrings("val it = \"a\\nb\" : string"),
        is(lines("val it = {|a", "b|} : string")));
    assertThat(
        OutputMatcher.toRawStrings(
            "val s = \"say \\\"hi\\\"\\n\\\\bye\" : string"),
        is(lines("val s = {|say \"hi\"", "\\bye|} : string")));
    // Unchanged: a tab, carriage return, control character or non-ASCII
    // character anywhere, which would be invisible or fragile in the script
    assertThat(
        OutputMatcher.toRawStrings("val s = \"a\\n\\tb\" : string"),
        is("val s = \"a\\n\\tb\" : string"));
    assertThat(
        OutputMatcher.toRawStrings("val s = \"a\\r\\nb\" : string"),
        is("val s = \"a\\r\\nb\" : string"));
    assertThat(
        OutputMatcher.toRawStrings("val s = \"a\\^Lb\\nc\" : string"),
        is("val s = \"a\\^Lb\\nc\" : string"));
    assertThat(
        OutputMatcher.toRawStrings("val s = \"a\\252\\nb\" : string"),
        is("val s = \"a\\252\\nb\" : string"));
    // A trailing newline leaves the closing fence alone on the last line
    assertThat(
        OutputMatcher.toRawStrings("val it = \"a\\n\" : string"),
        is(lines("val it = {|a", "|} : string")));
    // If the printer wrapped the literal onto the next line, the raw literal
    // starts on the next line too, indented; the type suffix may also have
    // been wrapped, and follows the closing fence.
    assertThat(
        OutputMatcher.toRawStrings(
            lines("val program =", "  \"a\\nb\" : string")),
        is(lines("val program =", "  {|a", "b|} : string")));
    assertThat(
        OutputMatcher.toRawStrings(
            lines("val program =", "  \"a\\nb\"", "  : string")),
        is(lines("val program =", "  {|a", "b|} : string")));
    assertThat(
        OutputMatcher.toRawStrings(lines("val it = \"a\\nb\"", "  : string")),
        is(lines("val it = {|a", "b|} : string")));
    // Several bindings, and surrounding lines, in one output
    assertThat(
        OutputMatcher.toRawStrings(
            lines(
                "val a = \"x\\ny\" : string",
                "val b = 1 : int",
                "val c = \"p\\nq\" : string")),
        is(
            lines(
                "val a = {|x",
                "y|} : string",
                "val b = 1 : int",
                "val c = {|p",
                "q|} : string")));
    // The fences carry an identifier if the content contains "|}"
    assertThat(
        OutputMatcher.toRawStrings("val it = \"a|}\\nb\" : string"),
        is(lines("val it = {a|a|}", "b|a} : string")));
    assertThat(
        OutputMatcher.toRawStrings("val it = \"|}|a}\\n\" : string"),
        is(lines("val it = {b||}|a}", "|b} : string")));
    assertThat(OutputMatcher.rawLiteral("x"), is("{|x|}"));
    // If the second line starts with a space, the content starts on the
    // line after the "{_|" fence; content that starts with a newline does
    // not need that, because a newline after "{|" is content
    assertThat(
        OutputMatcher.toRawStrings("val it = \"a\\n  b\" : string"),
        is(lines("val it = {_|", "a", "  b|_} : string")));
    assertThat(
        OutputMatcher.toRawStrings("val it = \"\\na\" : string"),
        is(lines("val it = {|", "a|} : string")));
    assertThat(
        OutputMatcher.toRawStrings("val it = \"a\\nb\\n  c\" : string"),
        is(lines("val it = {|a", "b", "  c|} : string")));
    assertThat(
        OutputMatcher.rawLiteral(lines("a", " b")),
        is(lines("{_|", "a", " b|_}")));
    assertThat(
        OutputMatcher.rawLiteral(lines("a|_}", " b")),
        is(lines("{_a|", "a|_}", " b|_a}")));
    final StringBuilder b = new StringBuilder("|}");
    for (char c = 'a'; c <= 'z'; c++) {
      b.append('|').append(c).append('}');
    }
    assertThat(OutputMatcher.rawLiteral(b.toString()), is("{aa|" + b + "|aa}"));
  }
}

// End TypeTest.java
