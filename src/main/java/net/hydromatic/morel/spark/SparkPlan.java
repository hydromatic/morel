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

import static java.util.Objects.requireNonNull;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.TextFormat;
import java.util.List;
import net.hydromatic.morel.foreign.SparkBackend;
import org.apache.spark.connect.proto.Plan;

/**
 * A Spark plan: what {@code Spark.prepare} makes of a Morel function, and what
 * {@code execute} runs.
 *
 * <p>Its text is the plan's own, in Spark's protobuf text format, with one
 * difference: a {@code LocalRelation} shows the number of rows it carries
 * rather than the Arrow bytes that carry them. The bytes are neither readable
 * nor stable -- the same rows encode differently from one Arrow version to the
 * next -- and the row count is what a reader wants.
 */
public class SparkPlan implements SparkBackend.Plan {
  private final Plan plan;
  private final ImmutableList<Integer> localRowCounts;

  SparkPlan(Plan plan, List<Integer> localRowCounts) {
    this.plan = requireNonNull(plan, "plan");
    this.localRowCounts = ImmutableList.copyOf(localRowCounts);
  }

  /** Returns the plan, to send to the server. */
  public Plan proto() {
    return plan;
  }

  @Override
  public String toString() {
    return text();
  }

  /** Returns the plan as text. */
  public String text() {
    final String text = TextFormat.printer().printToString(plan);
    if (localRowCounts.isEmpty()) {
      return stripTrailingNewline(text);
    }
    // A local relation's rows become a count. The relations appear in the
    // text in the order the translator made them, because the translator
    // walks the tree in the order the text prints it, so the counts can
    // be consumed in order.
    final StringBuilder b = new StringBuilder();
    int i = 0;
    for (String line : text.split("\n", -1)) {
      final int data = line.indexOf("data: \"");
      if (data >= 0 && line.trim().startsWith("data: \"")) {
        final int rows = localRowCounts.get(i++);
        b.append(line, 0, data)
            .append("data: [")
            .append(rows)
            .append(rows == 1 ? " row]" : " rows]")
            .append('\n');
      } else {
        b.append(line).append('\n');
      }
    }
    // The split added a trailing empty line; printToString already ended
    // with a newline.
    b.setLength(b.length() - 1);
    return stripTrailingNewline(b.toString());
  }

  /**
   * Removes the newline that the protobuf printer ends with; a plan's text is a
   * tree, not a line.
   */
  private static String stripTrailingNewline(String s) {
    return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
  }
}

// End SparkPlan.java
