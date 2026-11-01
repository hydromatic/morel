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
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.protobuf.ByteString;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.hydromatic.morel.compile.BuiltIn;
import net.hydromatic.morel.foreign.SparkBackend;
import net.hydromatic.morel.type.PrimitiveType;
import net.hydromatic.morel.type.Type;
import net.hydromatic.morel.type.TypeSystem;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.memory.RootAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowStreamWriter;
import org.apache.arrow.vector.util.Text;
import org.junit.jupiter.api.Test;

/** Tests {@link ArrowDecoder}. */
public class ArrowDecoderTest {
  private static TypeSystem typeSystem() {
    final TypeSystem typeSystem = new TypeSystem();
    BuiltIn.dataTypes(typeSystem, new ArrayList<>());
    return typeSystem;
  }

  private static final List<Object> NONE = ImmutableList.of("NONE");

  private static List<Object> some(Object o) {
    return ImmutableList.of("SOME", o);
  }

  /** Converts plain objects, as Arrow's getObject returns them. */
  @Test
  void testToValue() {
    final TypeSystem ts = typeSystem();
    final Type intList = ts.listType(PrimitiveType.INT);
    final Type intOption = ts.option(PrimitiveType.INT);
    assertThat(
        ArrowDecoder.toValue(true, PrimitiveType.BOOL, "c", 0), is(true));
    assertThat(
        ArrowDecoder.toValue((byte) 7, PrimitiveType.INT, "c", 0), is(7));
    assertThat(
        ArrowDecoder.toValue((short) 7, PrimitiveType.INT, "c", 0), is(7));
    assertThat(ArrowDecoder.toValue(7L, PrimitiveType.INT, "c", 0), is(7));
    assertThat(ArrowDecoder.toValue(2.5, PrimitiveType.REAL, "c", 0), is(2.5f));
    assertThat(
        ArrowDecoder.toValue(2.5f, PrimitiveType.REAL, "c", 0), is(2.5f));
    assertThat(
        ArrowDecoder.toValue(
            new BigDecimal("12.34"), PrimitiveType.REAL, "c", 0),
        is(12.34f));
    assertThat(
        ArrowDecoder.toValue(new Text("hi"), PrimitiveType.STRING, "c", 0),
        is("hi"));
    // binary -> word list, unsigned
    assertThat(
        ArrowDecoder.toValue(new byte[] {1, (byte) 255}, intList, "c", 0),
        is(ImmutableList.of(1L, 255L)));
    // list with nullable elements
    assertThat(
        ArrowDecoder.toValue(
            Arrays.asList(1, null, 3), ts.listType(intOption), "c", 0),
        is(ImmutableList.of(some(1), NONE, some(3))));
    // struct -> record, fields in label order, nullable field
    final Type record =
        ts.recordType(
            ImmutableMap.of("b", PrimitiveType.STRING, "a", intOption)
                .entrySet());
    final Map<String, Object> struct = new HashMap<>();
    struct.put("b", new Text("x"));
    struct.put("a", null);
    assertThat(
        ArrowDecoder.toValue(struct, record, "c", 0),
        is(ImmutableList.of(NONE, "x")));
    // option at top level
    assertThat(ArrowDecoder.toValue(null, intOption, "c", 0), is(NONE));
    assertThat(ArrowDecoder.toValue(5, intOption, "c", 0), is(some(5)));
    // date (days since epoch), timestamp (micros), timestamp_ntz
    final Type date = ts.lookup(BuiltIn.Eqtype.DATE);
    final Type time = ts.lookup(BuiltIn.Eqtype.TIME);
    assertThat(
        ArrowDecoder.toValue(19737, date, "c", 0),
        is(OffsetDateTime.of(2024, 1, 15, 0, 0, 0, 0, ZoneOffset.UTC)));
    assertThat(
        ArrowDecoder.toValue(1_705_314_600_000_000L, time, "c", 0),
        is(1_705_314_600_000_000_000L));
    assertThat(
        ArrowDecoder.toValue(1_705_314_600_000_000L, date, "c", 0),
        is(OffsetDateTime.of(2024, 1, 15, 10, 30, 0, 0, ZoneOffset.UTC)));
  }

  @Test
  void testErrors() {
    final TypeSystem ts = typeSystem();
    final SparkBackend.SparkException e =
        assertThrows(
            SparkBackend.SparkException.class,
            () -> ArrowDecoder.toValue(null, PrimitiveType.INT, "sal", 3));
    assertThat(e.errorClass, is("NULL_VALUE"));
    assertThat(
        e.getMessage(),
        is("Null in column sal of row 3, whose type int has no room for it"));
    final SparkBackend.SparkException e2 =
        assertThrows(
            SparkBackend.SparkException.class,
            () ->
                ArrowDecoder.toValue(
                    Long.MIN_VALUE, PrimitiveType.INT, "i64", 1));
    assertThat(e2.errorClass, is("CAST_OVERFLOW"));
    assertThat(
        e2.getMessage(),
        is(
            "Value -9223372036854775808 in column i64 of row 1 does not fit in int"));
    // A null inside a list whose elements are not option
    final SparkBackend.SparkException e3 =
        assertThrows(
            SparkBackend.SparkException.class,
            () ->
                ArrowDecoder.toValue(
                    Arrays.asList(1, null),
                    ts.listType(PrimitiveType.INT),
                    "arr",
                    0));
    assertThat(e3.errorClass, is("NULL_VALUE"));
  }

  /** Decodes an Arrow IPC stream, as Spark Connect sends one. */
  @Test
  void testDecodeStream() throws IOException {
    final TypeSystem ts = typeSystem();
    final ByteString batch;
    try (BufferAllocator allocator = new RootAllocator();
        IntVector id = new IntVector("id", allocator);
        VarCharVector s = new VarCharVector("s", allocator);
        Float8Vector x = new Float8Vector("x", allocator);
        BigIntVector big = new BigIntVector("big", allocator)) {
      id.allocateNew(2);
      s.allocateNew(2);
      x.allocateNew(2);
      big.allocateNew(2);
      id.set(0, 1);
      id.set(1, 2);
      s.set(0, "one".getBytes(StandardCharsets.UTF_8));
      s.setNull(1);
      x.set(0, 1.5);
      x.set(1, 2.5);
      big.set(0, 4L);
      big.setNull(1);
      for (FieldVector v : Arrays.asList(id, s, x, big)) {
        v.setValueCount(2);
      }
      try (VectorSchemaRoot root = VectorSchemaRoot.of(id, s, x, big);
          ByteArrayOutputStream out = new ByteArrayOutputStream();
          ArrowStreamWriter writer = new ArrowStreamWriter(root, null, out)) {
        writer.start();
        writer.writeBatch();
        writer.end();
        writer.close();
        batch = ByteString.copyFrom(out.toByteArray());
      }
    }
    // Row type in Morel's label order: big, id, s, x. "s" is option, so its
    // null decodes as NONE; "big" is plain, and its null raises.
    final Type rowType =
        ts.recordType(
            ImmutableMap.<String, Type>of(
                    "id",
                    PrimitiveType.INT,
                    "s",
                    ts.option(PrimitiveType.STRING),
                    "x",
                    PrimitiveType.REAL,
                    "big",
                    ts.option(PrimitiveType.INT))
                .entrySet());
    final List<Object> rows =
        ArrowDecoder.decode(ImmutableList.of(batch), rowType);
    assertThat(
        rows,
        is(
            ImmutableList.of(
                ImmutableList.of(some(4), 1, some("one"), 1.5f),
                ImmutableList.of(NONE, 2, NONE, 2.5f))));
    final Type strictRowType =
        ts.recordType(
            ImmutableMap.<String, Type>of(
                    "id", PrimitiveType.INT,
                    "s", ts.option(PrimitiveType.STRING),
                    "x", PrimitiveType.REAL,
                    "big", PrimitiveType.INT)
                .entrySet());
    final SparkBackend.SparkException e =
        assertThrows(
            SparkBackend.SparkException.class,
            () -> ArrowDecoder.decode(ImmutableList.of(batch), strictRowType));
    assertThat(e.errorClass, is("NULL_VALUE"));
    assertThat(
        e.getMessage(),
        is("Null in column big of row 1, whose type int has no room for it"));
  }
}

// End ArrowDecoderTest.java
