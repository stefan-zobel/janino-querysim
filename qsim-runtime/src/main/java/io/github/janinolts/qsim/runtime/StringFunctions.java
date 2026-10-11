/*
 * Copyright 2026 the janino-querysim authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.janinolts.qsim.runtime;

/**
 * The string functions of the simulated engine. All of them work on UTF-16
 * code units ({@code char}), not on code points, so that a generated loop over
 * {@code char} computes the same result as these methods. The arguments are
 * never {@code null}; the callers handle {@code NULL}.
 */
public final class StringFunctions {

    private StringFunctions() {
    }

    /**
     * Returns a substring.
     *
     * @param s the string
     * @param start the position of the first character, 1-based; values below
     *        1 count as 1
     * @param length the number of characters; values below 0 count as 0
     * @return the characters from {@code start} on, at most {@code length},
     *         and fewer at the end of the string
     */
    public static String substring(String s, int start, int length) {
        int n = s.length();
        long from = Math.min(Math.max((long) start - 1, 0), n);
        long to = Math.min(from + Math.max(length, 0), n);
        return s.substring((int) from, (int) to);
    }

    /**
     * Converts each UTF-16 code unit with {@link Character#toUpperCase(char)}.
     * Unlike {@link String#toUpperCase()}, this never changes the length:
     * the sharp s (U+00DF) stays unchanged, and so do surrogates.
     */
    public static String upper(String s) {
        char[] chars = s.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = Character.toUpperCase(chars[i]);
        }
        return new String(chars);
    }

    /**
     * Matches a string against a {@code LIKE} pattern: {@code %} matches any
     * sequence of code units, also an empty one, {@code _} exactly one code
     * unit, every other code unit itself. There is no escape character.
     */
    public static boolean like(String s, String pattern) {
        int si = 0;
        int pi = 0;
        int starPattern = -1;
        int starString = 0;
        while (si < s.length()) {
            if (pi < pattern.length()) {
                char pc = pattern.charAt(pi);
                if (pc == '%') {
                    // Remember the position, first try to match an empty sequence.
                    starPattern = pi++;
                    starString = si;
                    continue;
                }
                if (pc == '_' || pc == s.charAt(si)) {
                    si++;
                    pi++;
                    continue;
                }
            }
            if (starPattern < 0) {
                return false;
            }
            // Let the last % match one more code unit, and retry.
            pi = starPattern + 1;
            si = ++starString;
        }
        while (pi < pattern.length() && pattern.charAt(pi) == '%') {
            pi++;
        }
        return pi == pattern.length();
    }
}
