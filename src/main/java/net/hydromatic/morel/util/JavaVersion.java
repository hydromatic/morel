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
package net.hydromatic.morel.util;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Version of the JDK.
 *
 * <p>The {@link #components} field contains the Java version string,
 * parsed into a list of integers. For example,
 * Java "13.0.2" is the list [13, 0, 2];
 * Java "1.8.0_341" is the list [8, 0, 341]. */
public class JavaVersion implements Comparable<JavaVersion> {
  public final List<Integer> components;

  /** Version of the current JVM. */
  public static final JavaVersion CURRENT;

  static {
    String versionString = System.getProperty("java.version");
    String[] versions = versionString.split("[._]");
    List<Integer> list = new ArrayList<>();
    for (String version : versions) {
      list.add(Integer.parseInt(version));
    }
    CURRENT = new JavaVersion(list);
  }

  private static final Comparator<Iterable<Integer>> COMPARATOR =
      Ordering.<Integer>natural().lexicographical();

  /** Private constructor. */
  private JavaVersion(List<Integer> components) {
    this.components = ImmutableList.copyOf(components);
  }

  /** Creates a version. */
  public static JavaVersion of(int... components) {
    List<Integer> list = new ArrayList<>();
    for (int component : components) {
      list.add(component);
    }
    return of(list);
  }

  /** Creates a version. */
  public static JavaVersion of(List<Integer> componentList) {
    if (componentList.size() >= 2
        && componentList.get(0) == 1
        && componentList.get(1) <= 9) {
      // JDK 1.8.x -> 8.x
      componentList = componentList.subList(1, componentList.size());
    }
    return new JavaVersion(ImmutableList.copyOf(componentList));
  }

  @Override public int compareTo(JavaVersion o) {
    return COMPARATOR.compare(components, o.components);
  }
}

// End JavaVersion.java
