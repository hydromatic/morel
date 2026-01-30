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
  }
}

// End TypeTest.java
