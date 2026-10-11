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

package io.github.janinolts.qsim.harness;

import io.github.janinolts.qsim.backend.GeneratedUnit;
import io.github.janinolts.qsim.backend.Instantiation;
import io.github.janinolts.qsim.runtime.Evaluator;
import io.github.janinolts.qsim.runtime.GeneratedClass;

import java.util.stream.IntStream;

/**
 * Hand-written units, valid Java 8, that every backend must compile with the
 * same result.
 */
final class HandWrittenUnits {

    static final String PACKAGE = "qsim.generated";

    static final int[] VALUES = IntStream.rangeClosed(1, 10).toArray();

    private HandWrittenUnits() {
    }

    /** A class in the style of Spark: a class body with a superclass, instantiated without arguments. */
    static GeneratedUnit wholeStage() {
        return GeneratedUnit.ofClass(PACKAGE, "WholeStage", """
                    private int calls;

                    public Object generate(Object[] references) {
                        int[] values = (int[]) references[0];
                        Counter counter = new Counter();
                        long sum = 0;
                        try {
                            for (int i = 0; i < values.length; i++) {
                                sum += weight(values[i]);
                                counter.add(1);
                            }
                        } finally {
                            calls++;
                        }
                        return "sum=" + sum + ",count=" + counter.count + ",calls=" + calls;
                    }

                    private static int weight(int v) {
                        switch (v % 3) {
                        case 0:
                            return v;
                        case 1:
                            return -v;
                        default:
                            return 2 * v;
                        }
                    }

                    private static final class Counter {
                        int count;

                        void add(int n) {
                            count += n;
                        }
                    }
                """)
                .extendedClass(GeneratedClass.class.getName())
                .references((Object) VALUES)
                .build();
    }

    /** The result of {@link #wholeStage()}, computed here. */
    static String wholeStageResult() {
        long sum = 0;
        for (int v : VALUES) {
            sum += switch (v % 3) {
                case 0 -> v;
                case 1 -> -v;
                default -> 2 * v;
            };
        }
        return "sum=" + sum + ",count=" + VALUES.length + ",calls=1";
    }

    /** A class in the style of Flink: a constructor that takes the references. */
    static GeneratedUnit operatorClass() {
        return GeneratedUnit.ofClass(PACKAGE, "Scaler", """
                    private final int factor;

                    public Scaler(Object[] references) {
                        this.factor = ((Integer) references[0]).intValue();
                    }

                    public Object evaluate(Object[] arguments) {
                        return Integer.valueOf(factor * ((Integer) arguments[0]).intValue());
                    }
                """)
                .imports("java.util.*")
                .implementedInterfaces(Evaluator.class.getName())
                .instantiation(Instantiation.REFERENCES_CONSTRUCTOR)
                .references(3)
                .build();
    }

    /** The expression of the benchmark workload {@code TINY} of janino-lts. */
    static GeneratedUnit expression() {
        return GeneratedUnit.ofExpression(PACKAGE, "Tiny", "a * 3 + b", "int")
                .parameter("int", "a")
                .parameter("int", "b")
                .build();
    }

    /** A script with a loop and a compound assignment. */
    static GeneratedUnit script() {
        return GeneratedUnit.ofScript(PACKAGE, "Factorial", """
                        long f = 1;
                        for (int i = 2; i <= n; i++) {
                            f *= i;
                        }
                        return f;
                """, "long")
                .parameter("int", "n")
                .build();
    }

    /** A class with a type error in the second line of its body. */
    static GeneratedUnit invalid() {
        return GeneratedUnit.ofClass(PACKAGE, "Invalid", """
                    public Object generate(Object[] references) {
                        int x = "text";
                        return null;
                    }
                """)
                .extendedClass(GeneratedClass.class.getName())
                .build();
    }
}
