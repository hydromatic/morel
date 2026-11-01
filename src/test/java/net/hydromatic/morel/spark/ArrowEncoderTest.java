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
package net.hydromatic.morel.spark;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.protobuf.ByteString;
import java.util.ArrayList;
import java.util.List;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.RecordType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link ArrowEncoder}, by decoding what it encodes: the rows that come
 * back are the rows that went in, which is the only thing a reader of the bytes
 * could ask.
 */
public class ArrowEncoderTest {
  private final TypeSystem typeSystem = typeSystem();

  /**
   * Returns a type system that knows the built-in datatypes, "option" among
   * them.
   */
  private static TypeSystem typeSystem() {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    return typeSystem;
  }

  /** Encodes rows and decodes them again. */
  private List<Object> roundTrip(List<Object> rows, Type rowType) {
    final ByteString bytes = ArrowEncoder.encode(rows, rowType);
    assertThat(bytes.size(), is(greaterThan(0)));
    return ArrowDecoder.decode(ImmutableList.of(bytes), rowType);
  }

  /** Returns the record type of the rows of query 1 of the seed corpus. */
  private Type rowType() {
    return typeSystem.recordType(
        ImmutableSortedMap.<String, Type>orderedBy(RecordType.ORDERING)
            .put("b", PrimitiveType.BOOL)
            .put("i", PrimitiveType.INT)
            .put("s", PrimitiveType.STRING)
            .put("x", PrimitiveType.REAL)
            .build());
  }

  /**
   * Tests the rows of query 1 of the seed corpus, which is what pins the local
   * relation's encoding: one column of each of the four types a local relation
   * can hold.
   */
  @Test
  void testRows() {
    final List<Object> rows =
        ImmutableList.of(
            ImmutableList.of(true, 1, "one", 1.5f),
            ImmutableList.of(false, 2, "two", 2.5f),
            ImmutableList.of(true, 3, "three", 3.5f));
    assertThat(roundTrip(rows, rowType()), is(rows));
  }

  /** Tests that no rows is a batch too, since a query may read an empty bag. */
  @Test
  void testNoRows() {
    assertThat(
        roundTrip(ImmutableList.of(), rowType()), is(ImmutableList.of()));
  }

  /** Tests a collection of values rather than of records. */
  @Test
  void testScalarRows() {
    final List<Object> rows = ImmutableList.of(1, 2, 3);
    assertThat(roundTrip(rows, PrimitiveType.INT), is(rows));
  }

  /**
   * Tests an optional column: {@code NONE} is the null, and it is the only
   * thing that may be null.
   */
  @Test
  void testOption() {
    final Type type = typeSystem.option(PrimitiveType.STRING);
    final Type rowType =
        typeSystem.recordType(
            ImmutableSortedMap.<String, Type>orderedBy(RecordType.ORDERING)
                .put("i", PrimitiveType.INT)
                .put("s", type)
                .build());
    final List<Object> rows =
        ImmutableList.of(
            ImmutableList.of(1, ImmutableList.of("SOME", "one")),
            ImmutableList.of(2, ImmutableList.of("NONE")));
    assertThat(roundTrip(rows, rowType), is(rows));
  }

  /** Tests that a type Spark has no column for is refused, not guessed at. */
  @Test
  void testUnsupportedType() {
    final Type rowType = typeSystem.listType(PrimitiveType.INT);
    final SparkBackend.SparkException e =
        assertThrows(
            SparkBackend.SparkException.class,
            () -> ArrowEncoder.encode(ImmutableList.of(), rowType));
    assertThat(e.errorClass, is("UNSUPPORTED_TYPE"));
  }
}

// End ArrowEncoderTest.java
