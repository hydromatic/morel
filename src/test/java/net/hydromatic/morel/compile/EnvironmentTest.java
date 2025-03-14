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
package net.hydromatic.morel.compile;

import static net.hydromatic.morel.ast.CoreBuilder.core;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsInstanceOf.instanceOf;

import com.google.common.collect.ImmutableSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import net.hydromatic.morel.type.PrimitiveType;
import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Matcher;
import org.junit.jupiter.api.Test;

/** Tests for {@link net.hydromatic.morel.compile.Environment}. */
public class EnvironmentTest {
  /**
   * Tests that if you call {@link Environment#bind} twice with the same name,
   * the binding chain does not get longer.
   */
  @Test
  void testOptimizeSubEnvironment() {
    final Environment e0 =
        Environments.empty()
            .bind(core.idPat(PrimitiveType.INT, "a", 0), 0)
            .bind(core.idPat(PrimitiveType.INT, "b", 0), 1)
            .bind(core.idPat(PrimitiveType.INT, "c", 0), 2);
    assertThat(e0, instanceOf(Environments.SubEnvironment.class));
    checkOptimizeSubEnvironment(e0);

    final Environment e0a = e0.bindAll(e0.getValueMap().values());
    assertThat(e0a, instanceOf(Environments.MapEnvironment.class));
    checkOptimizeSubEnvironment(e0a);
  }

  private void checkOptimizeSubEnvironment(Environment e0) {
    final Set<String> nameSet = ImmutableSet.of("false", "true", "a", "b", "c");
    final Set<String> namePlusFooSet =
        ImmutableSet.<String>builder().addAll(nameSet).add("foo").build();

    assertThat(e0.getValueMap().keySet(), is(nameSet));
    assertThat(e0, hasEnvLength(5));

    // Overwrite "true"; there are still 5 values, but 6 bindings.
    final Environment e1 =
        e0.bind(core.idPat(PrimitiveType.STRING, "true", 0), "yes");
    assertThat(e1.getValueMap().keySet(), is(nameSet));
    assertThat(e1, hasEnvLength(6));

    // Overwrite "true" again; still 5 values, and still 6 bindings.
    final Environment e2 =
        e1.bind(core.idPat(PrimitiveType.STRING, "true", 0), "no");
    assertThat(e2.getValueMap().keySet(), is(nameSet));
    assertThat(e2, hasEnvLength(6));

    // Add "foo". Value count and binding count increase.
    final Environment e3 =
        e2.bind(core.idPat(PrimitiveType.STRING, "foo", 0), "baz");
    assertThat(e3.getValueMap().keySet(), is(namePlusFooSet));
    assertThat(e3, hasEnvLength(7));

    // Add "true". Value count stays at 7, binding count increases.
    // (We do not look beyond the "foo" for the "true"; such optimization would
    // be nice, but is expensive, so we do not do it.)
    final Environment e4 =
        e3.bind(core.idPat(PrimitiveType.STRING, "true", 0), "yes");
    assertThat(e4.getValueMap().keySet(), is(namePlusFooSet));
    assertThat(e4, hasEnvLength(8));
  }

  private Matcher<Environment> hasEnvLength(int i) {
    return new CustomTypeSafeMatcher<Environment>("environment depth " + i) {
      @Override
      protected boolean matchesSafely(Environment env) {
        return depth(env) == i;
      }

      private int depth(Environment env) {
        final AtomicInteger c = new AtomicInteger();
        env.visit(b -> c.incrementAndGet());
        return c.get();
      }
    };
  }
}

// End EnvironmentTest.java
