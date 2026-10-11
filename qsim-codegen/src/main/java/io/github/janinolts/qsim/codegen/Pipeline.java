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

package io.github.janinolts.qsim.codegen;

import io.github.janinolts.qsim.plan.PlanNode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A pipeline: a scan followed by operators that process one row at a time,
 * here {@code Filter} and {@code Project}, which generated code fuses into one
 * loop.
 *
 * @param scan the scan at the bottom
 * @param operators the operators above it, from the bottom up
 */
record Pipeline(PlanNode.Scan scan, List<PlanNode> operators) {

    /**
     * Decomposes a plan into a pipeline.
     *
     * @throws IllegalArgumentException if the plan contains an operator that
     *         ends a pipeline, which is not supported yet
     */
    static Pipeline of(PlanNode plan) {
        List<PlanNode> operators = new ArrayList<>();
        PlanNode node = plan;
        while (true) {
            switch (node) {
                case PlanNode.Scan s -> {
                    Collections.reverse(operators);
                    return new Pipeline(s, List.copyOf(operators));
                }
                case PlanNode.Filter f -> {
                    operators.add(f);
                    node = f.input();
                }
                case PlanNode.Project p -> {
                    operators.add(p);
                    node = p.input();
                }
                default -> throw new IllegalArgumentException("Not a pipeline of filters and projections: " + node);
            }
        }
    }
}
