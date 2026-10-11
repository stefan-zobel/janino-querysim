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

/**
 * The harness: loads the backends, compiles each unit with each of them, runs
 * the results and compares them. The class path of the harness contains
 * neither Janino nor the Janino adapter; each Janino version is loaded into a
 * class loader of its own (see {@link io.github.janinolts.qsim.harness.JaninoInstallation}).
 */
package io.github.janinolts.qsim.harness;
