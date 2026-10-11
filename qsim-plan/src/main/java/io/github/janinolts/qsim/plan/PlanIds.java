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

package io.github.janinolts.qsim.plan;

import io.github.janinolts.qsim.runtime.Position;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * The identifiers of the nodes of a plan: their indexes in a pre-order
 * traversal, by identity, so that two equal nodes in different places have
 * different identifiers. Every implementation of a plan, interpreted or
 * generated, labels the sources of its pipelines with them.
 */
public final class PlanIds {

    private final Map<PlanNode, Integer> ids = new IdentityHashMap<>();
    private final List<PlanNode.Scan> scans = new ArrayList<>();

    /** Numbers the nodes of a plan. */
    public PlanIds(PlanNode plan) {
        visit(plan);
    }

    private void visit(PlanNode node) {
        ids.put(node, ids.size());
        if (node instanceof PlanNode.Scan s) {
            scans.add(s);
        }
        for (PlanNode child : node.children()) {
            visit(child);
        }
    }

    /** Returns the identifier of a node of the plan. */
    public int id(PlanNode node) {
        Integer id = ids.get(node);
        if (id == null) {
            throw new IllegalArgumentException("Not a node of the plan: " + node);
        }
        return id;
    }

    /**
     * Returns the label of a node as a source of rows: the table name of a
     * scan, {@code node:<id>} for an operator that collects its input.
     */
    public String sourceLabel(PlanNode node) {
        return node instanceof PlanNode.Scan s ? s.table() : Position.node(id(node));
    }

    /** Returns the scans of the plan, in pre-order: the order of the inputs of generated code. */
    public List<PlanNode.Scan> scans() {
        return List.copyOf(scans);
    }
}
