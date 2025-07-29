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

import java.text.Collator;
import java.text.ParseException;
import java.text.RuleBasedCollator;
import java.util.Comparator;
import java.util.Locale;

/**
 * Comparator that respects word boundaries and punctuation.
 *
 * <p>It compares words in a way that is more natural for humans, taking into
 * account punctuation such as commas, periods, and spaces.
 *
 * <p>For example, it will compare "a" before "ab" as you would expect; but it
 * compares "a c" before "ab c" because of the word boundary.
 *
 * <p>It is immutable, thread-safe, and has the same behavior in all locales.
 */
public enum WordComparator implements Comparator<String> {
  INSTANCE;

  private final Collator collator;

  {
    try {
      RuleBasedCollator rootCollator =
          (RuleBasedCollator) Collator.getInstance(Locale.ROOT);
      collator =
          new RuleBasedCollator(
              rootCollator.getRules() + " & '(' < ')' < ' ' < '_' < 'A'");
    } catch (ParseException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public int compare(String o1, String o2) {
    int i = 0;
    boolean inverse = false;
    for (; ; ) {
      int i1 = findPunctuation(i, inverse, o1);
      int i2 = findPunctuation(i, inverse, o2);
      if (i1 < 0 && i2 < 0) {
        return collator.compare(o1, o2);
      } else if (i1 < 0) {
        // o1 is not followed by punctuation.
        // Compare the parts before the punctuation.
        int c = collator.compare(o1.substring(i), o2.substring(i, i2));
        if (c != 0) {
          return c;
        }
        // o2 is longer
        return -1;
      } else if (i2 < 0) {
        // o2 is not followed by punctuation.
        // Compare the parts before the punctuation.
        int c = collator.compare(o1.substring(i, i1), o2.substring(i));
        if (c != 0) {
          return c;
        }
        // o1 is longer
        return 1;
      } else {
        // Compare the parts before the punctuation.
        int c = collator.compare(o1.substring(i, i1), o2.substring(i, i2));
        if (c != 0) {
          return c;
        }
        // Compare the punctuation, then the words after the punctuation.
        inverse = !inverse;
        i = i1;
      }
    }
  }

  private int findPunctuation(int i, boolean inverse, String s) {
    for (; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean p = isPunctuation(c);
      if (p != inverse) {
        return i;
      }
    }
    return -1;
  }

  private boolean isPunctuation(char c) {
    return ".,;:!? _()".indexOf(c) >= 0;
  }
}

// End WordComparator.java
