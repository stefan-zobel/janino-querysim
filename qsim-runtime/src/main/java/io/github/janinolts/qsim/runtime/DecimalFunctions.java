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

import java.math.BigDecimal;
import java.math.MathContext;

/**
 * The decimal operations that {@link BigDecimal} does not define exactly.
 * Addition, subtraction, multiplication, negation, remainder and comparison
 * are the methods of {@link BigDecimal} itself.
 */
public final class DecimalFunctions {

    /** The precision of a decimal division: 16 digits, rounded half-even. */
    public static final MathContext DIVISION = MathContext.DECIMAL64;

    private DecimalFunctions() {
    }

    /**
     * Divides with the precision of {@link #DIVISION}.
     *
     * @throws ArithmeticException if the divisor is zero
     */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        return dividend.divide(divisor, DIVISION);
    }
}
