# janino-querysim (qsim)

qsim generates Java code in the style of query engines, compiles it with two versions of
[Janino](https://github.com/janino-lts/janino) and with javac, and compares:

- **results:** every unit runs on generated data; Janino must give the same rows, exceptions and
  compile errors as javac and as an interpreter of the query plan;
- **class files:** the baseline and the candidate must generate the same code, or the difference
  must be known;
- **compile time and allocation:** an interleaved A/B measurement with controls, replicated on
  several machines.

The baseline is Janino 3.1.12 (`org.codehaus.janino:janino`), the version that Spark, Calcite
and Flink use. The candidate is a version of janino-lts (`io.github.janino-lts:janino`), by
default the last released 3.1.x version. A backend is named by its ID: `JAVAC`, `J3112` for the
baseline, `JLTS` for the candidate and `INTERP` for the interpreter. A candidate of the
development line (`master`) can also run in its compliance mode (`JaninoOption.JAVAC_COMPLIANCE`),
as `JLTS-C`.

The generated code takes four shapes, one per *style*:

| Style | Modeled after | Janino API |
| --- | --- | --- |
| `WHOLE_STAGE` | Spark whole-stage code generation | `ClassBodyEvaluator` |
| `OPERATOR_CLASSES` | Flink operators | `SimpleCompiler` |
| `ENUMERABLE` | Calcite `Enumerable` | `ICompilerFactory` (commons-compiler) |
| `EXPRESSION` | expression evaluators | `ExpressionEvaluator`, `ScriptEvaluator` |

## Building

qsim needs JDK 21 or later to build and run; the generated code and class files are Java 8.

```
./mvnw verify
```

The build runs the unit tests and creates, in `qsim-bench/target`:

- `qsim.jar`, the executable JAR with qsim and its libraries, without Janino;
- `janino-baseline` and `janino-candidate`, each with the JARs of one Janino version and the
  adapter `qsim-backend-janino`.

Each Janino version is loaded in a class loader of its own, so both run in the same JVM. The
versions come from the properties `qsim.baseline.version` and `qsim.candidate.version` of the
POM (versions up to 3.1.14 have the group ID `org.codehaus.janino`, so set
`qsim.candidate.groupId` for them). At run time, `-Dqsim.baseline.dir` and
`-Dqsim.candidate.dir` select other directories; each must contain exactly one `janino-*.jar`,
one `commons-compiler-*.jar` and one `qsim-backend-janino-*.jar`.

| Module | Content |
| --- | --- |
| `qsim-runtime` | The classes that the generated code calls (Java 8) |
| `qsim-backend-api` | The interface of a compiler backend |
| `qsim-plan` | Query plans, data generator, interpreter, random plans |
| `qsim-codegen` | The generator of the four styles and the language profiles |
| `qsim-backend-janino` | The adapter for Janino, compiled against the API of 3.1.12 |
| `qsim-backend-javac` | javac with `--release 8` |
| `qsim-harness` | Differential comparison, class file comparison, ledger, minimizer, suites |
| `qsim-bench` | Measurement loop, statistics, reports and the command line |

## Command line

```
java -jar qsim.jar check     --suite smoke|regression [--compliance] [--out <dir>]
java -jar qsim.jar fuzz      [--seeds 1..2000] [--styles all] [--profiles STRICT|all] [--negatives] [--try-jumps] [--compliance] [--out <dir>]
java -jar qsim.jar minimize  --unit "<seed> <style> <profile> <variant>" --pair J3112:JLTS [--out <dir>]
java -jar qsim.jar dump      --unit "<seed> <style> <profile> <variant>" | --scenario Q_AGG --style WHOLE_STAGE
java -jar qsim.jar bench     [--mode xint,tiered] [--forks 3] [--rounds 40] [--points <patterns>] [P1 ... P10]
java -jar qsim.jar aggregate [--delta 0.02] [--out <dir>] <dir>...
```

`java -jar qsim.jar` without arguments lists all options. Unless `--out` names another
directory, `check` and `fuzz` write to `target/qsim-reports/<time>-<suite>/`, `minimize` to
`repro/<key>/`, `dump` to `dump/`, `bench` its raw data to `results/<replica>/`, and `aggregate`
its report into the first directory of raw data.

## Correctness

| Suite | Content |
| --- | --- |
| `smoke` | The nine fixed scenarios in size S, all styles, profile `STRICT`; under a minute |
| `regression` | The corpus (`qsim-harness/src/main/resources/corpus/seeds.txt`) and every seed that the ledger names |
| `campaign` | `fuzz`: random plans in child JVMs, any seed range, all profiles, with negative programs |

Every unit runs with all backends. A *difference* is a pair of backends (e.g. `J3112:JLTS`) and a
kind: `WRONG` (other rows), `INVALID` (other exception), `REJECTED` (one compiles, the other does
not), `LOCATION` (other position of a compile error), `BYTES` (other class files). A difference
between `INTERP` and `JAVAC` is a defect of qsim. Differences that a language profile expects,
e.g. a feature that javac accepts and Janino does not, are counted separately and are no findings.

With `--compliance`, every unit also runs with `JLTS-C`, the candidate in its compliance mode, and
the pair `JAVAC:JLTS-C` shows what that mode does not yet correct. Its expectation follows the
catalog of language features: like javac for the features that the compliance mode of the
candidate's version corrects (S-01, S-02, S-04, S-12 and S-13 since 3.2.0), otherwise like
`JLTS`. The option needs a candidate of the development line; with a 3.1 version, the command
stops with an error.

A campaign runs every seed in the variant `BASE` and in one *metamorphic variant*: the same plan,
generated with other settings, e.g. without method splitting or with nested `try` statements.
All variants of a seed must give the same result. `--variants all` runs all of them, `none` only
`BASE`. The variant `TRY_JUMPS` runs only with `--try-jumps`: nested `try` statements that a
`break` leaves, the code that Janino generates differently since 3.1.14 (janino-lts/janino#3).
Because it is not among the variants that seeds get in turn, the same arguments still generate
the same units with and without it.

### Ledger

The known differences are in `qsim-harness/src/main/resources/ledger/<line>/known-differences.tsv`,
one file per development line of the candidate: `3.1.x` for 3.1 versions, `master` for all
others. Each entry has a unit key (with `*`), a pair, a kind, an ID, a note, optionally feature
tags and optionally the version that fixed the difference. An entry with such a version applies
only to candidates before it (a snapshot of the version counts as fixed), so that the ledger of a
line serves its releases before and after the fix. Entries of the pair `JAVAC:JLTS-C` apply only
to runs with `--compliance`. A run fails on a difference without an entry
and on an entry whose unit ran without the difference. An ID `#N` is an issue of janino-lts/janino; the reports print it as
`janino-lts/janino#N`, so that it does not refer to the issues of the repository that runs qsim.
Other IDs, e.g. `S-04`, are entries of the register of deviations of janino-lts. Change the
ledger deliberately, by a pull request.

## Performance

A measurement point is a unit and an operation on it, e.g. compiling, loading and instantiating
it with a new compiler instance. Four arms run in the same JVM, in short rounds and in random
order:

| Arm | Content | Purpose |
| --- | --- | --- |
| A | Baseline | Reference |
| B | Candidate | The measurement: B/A, below 1 means the candidate is faster |
| B' | Candidate in a second class loader | Negative control: B'/B must be 1 |
| B+ | Candidate, which then waits actively for another 1/33 of its time | Positive control: B+/B must be about 1.03 |

In P1 (except in `xint`) and P9, a fifth arm `J` runs javac as a reference: in P1 its compile
time, in P9 the runtime of the code it compiled. The report gives J/A with its interval, only for
orientation; it changes no classification. In P1, `J` executes a number of operations per round
of its own, because javac takes a multiple of the time of Janino; a replica of P1 in `tiered`
takes about half as long again with it (25 to 28 instead of 16 to 19 minutes on GitHub runners).

By default, B+ compiles with the same copy of the candidate as B, so that this copy runs twice per
round. For the diagnosis of the controls, `bench --plus-loader own` gives B+ a copy of its own,
`--plus-loader prime` lets it share the copy of B', and `--prime-first` loads the copy of B' before
the copy of B; the report names the copies under "Environment".

Rounds are grouped into forks (fresh JVMs), forks into replicas (machines). The measured rounds
of a point have a time budget per fork (`--point-seconds 240`): once the next round would exceed
it, the point ends, but not before `--min-rounds 8`. Only very slow points reach it, e.g. the
highest levels of P2 in `xint`, where one compilation takes 10 to 25 seconds on a runner; the
report notes the rounds of such a point. The report gives
B/A with a 95 % interval and a classification: `SAME` (within 2 %), `FASTER` or `SLOWER` (selected
by Benjamini-Hochberg, adjusted interval excludes 1, positive control detected, `xint` and the
allocation agree), `INCONCLUSIVE` (anything else, with the minimum detectable difference) or
`INVALID` (the negative control fails). Time ratios never fail a run. The allocation in `xint`
does: B/A above 1.01 at a point needs an `ALLOC` entry in the ledger.

| ID | Measurement |
| --- | --- |
| P1 | Compile time per scenario; javac as a reference (except in `xint`) |
| P2 | Scaling over the size of the source, with the search for the 64 KB limit of a method |
| P3 | Phases: parsing, compiling, loading with verification, instantiating |
| P4 | Cold start in a fresh JVM |
| P5 | Concurrency, 1 to 16 threads |
| P6 | Class resolution: referenced classes and overloaded methods |
| P7 | Debugging information |
| P8 | Class files: sizes, largest method, comparison level L0 to L3 |
| P9 | Runtime of the generated code, where the method bodies differ; javac as a reference |
| P10 | Target version: loading class files of version 52 instead of 50 |

Modes: `xint` (`-Xint`, the work of the compiler without JIT effects), `c1` and `tiered`.

## The workflow

The workflow "qsim" (`.github/workflows/qsim.yml`) runs only when started manually: "Actions" >
"qsim" > "Run workflow".

| Input | Meaning | Default |
| --- | --- | --- |
| `baseline` | A version of `org.codehaus.janino:janino` on Maven Central | `3.1.12` |
| `candidate` | `latest-3.1.x` or a version of `io.github.janino-lts:janino` on Maven Central | `latest-3.1.x` |
| `candidate-ref` | Build the candidate from this branch, tag or commit of janino-lts/janino instead, e.g. `master` | empty |
| `javac-compliance` | Also run the candidate in its compliance mode (`JLTS-C`) in `smoke`, `regression` and `campaign`; needs a candidate of the development line, e.g. `candidate-ref` `master` | `false` |
| `suites` | From `smoke`, `regression`, `campaign`, `bench` | `smoke,regression` |
| `java` | Java versions that run qsim, from 21 and 25 | `21` |
| `seeds` | Seed range of `campaign` | `1..2000` |
| `campaign-options` | Further options of `fuzz` for `campaign`, e.g. `--try-jumps` | empty |
| `measurements` | Measurements of `bench` | `P1,P8` |
| `points` | Optional patterns of measurement points, e.g. to confirm a finding | empty |
| `modes` | From `xint`, `c1`, `tiered` | `xint,tiered` |
| `replicas` | Runners per Java version for `bench` | `6` |
| `forks`, `rounds` | Forks per replica, rounds per fork | `3`, `40` |

Jobs: `build` builds qsim, runs its unit tests and assembles `qsim.jar` with both Janino
versions (artifact `dist`); `correctness` runs the suites per Java version; `performance` runs
one job per Java version and replica, each on a VM of its own; `report` combines the replicas,
writes the job summary and uploads the artifact `qsim-reports` with the reports, the raw data,
the reproducers and the JARs of the run. The run fails on an unexplained difference, an orphaned
ledger entry, an unexplained allocation above the limit and unexpected class files of P8.

The job summary lists the inputs, the resolved versions, the result and duration of every suite
and job, and the reports; the duration of each measurement and mode is in the section
Environment of a performance report.

## Reproducing a finding

1. In the correctness report, the section "Unexplained differences" names the unit key, e.g.
   `seed:4711/WHOLE_STAGE/STRICT/...`, and the directory of its reproducer under `repro/`.
2. The reproducer is minimized: `case.txt` describes the difference and the result of each
   backend, `plan.txt` and `data.txt` the query, `source-<n>.txt` the code. `Repro.java` needs
   only `qsim.jar` (for the runtime classes) and the JARs of one Janino version; the command is
   at the end of `case.txt`:

   ```
   java -cp "qsim.jar:janino-3.1.19.jar:commons-compiler-3.1.19.jar" Repro.java
   ```

   (on Windows with `;` instead of `:`). Run it once with each Janino version. For a difference
   of the pair `JAVAC:JLTS-C`, the command sets `-Dorg.codehaus.janino.javacCompliance=true`, which
   switches every compiler of the development line to its compliance mode.
3. Without a reproducer, or to minimize again:

   ```
   java -jar qsim.jar dump     --unit "4711 WHOLE_STAGE STRICT BASE"
   java -jar qsim.jar minimize --unit "4711 WHOLE_STAGE STRICT BASE" --pair J3112:JLTS
   java -jar qsim.jar fuzz     --seeds 4711 --profiles all --negatives
   ```

   `minimize` with `--pair JAVAC:JLTS-C` runs the candidate in its compliance mode as well.

   A unit is fully defined by seed, style, profile, variant and, for a negative program, its
   mutation; the same arguments always generate the same code and data.
4. The artifact `qsim-reports` contains `artifacts/dist`, the `qsim.jar` and Janino JARs of the
   run, so a finding can be reproduced with exactly these versions.

If the difference is explained (a known change of Janino), add an entry to the ledger of the
line; add the seed to the corpus if it is a good regression test.

## Reproducing a measurement

1. The performance report names each point, e.g. `Q_AGG:M:ROWS/WHOLE_STAGE` in P1, and its mode.
2. Measure it again on GitHub runners: start the workflow with `suites` = `bench`, the
   measurement in `measurements`, the point in `points` and twice the replicas. A `FASTER` or
   `SLOWER` becomes a finding only if such a confirmation run agrees.
3. Or measure locally with the JARs of the run (`artifacts/dist` of `qsim-reports`):

   ```
   java -jar qsim.jar bench --mode xint --forks 3 --rounds 40 --points "Q_AGG:M:ROWS/WHOLE_STAGE" --replica local P1
   java -jar qsim.jar aggregate results
   ```

   The seed of the order of the arms is 1 unless `--seed` says otherwise, so the same arguments
   measure the same units in the same order. `aggregate` accepts raw data of any origin, e.g.
   the replicas of the artifact together with local ones, but compare only replicas of the same
   Java version and Janino versions.

## Reports and raw data

| File | Content |
| --- | --- |
| `report-correctness.md` | Differences by pair, kind and feature tag; findings with their reproducers; reconciliation with the ledger |
| `units.jsonl` | Per unit and backend: key, feature tags, outcome, digest of the result, class file sizes, comparison level |
| `report-performance.md` | Per point and mode: B/A with interval, classification, both controls, minimum detectable difference, allocation; per fork the median, p10 and p90 |
| `rounds-*.jsonl` | Per round and arm: time, CPU time, allocation, GC time, context switches |
| `points.jsonl` | Per point: key, source length, parameters, measured or why not |
| `classfiles.jsonl`, `debug-classfiles.jsonl`, `limits.jsonl` | Class files of P8 and P7, the limit search of P2 |
| `environment.json` | Per replica: JDK, OS, runner image, CPU, cores, JVM options, versions, commit, durations |
| `scaling/*.csv`, `scaling/*.svg` | The scaling series of P2 |
| `repro/<key>/` | Minimized reproducers |

## License

Apache License 2.0, see [LICENSE](LICENSE).
