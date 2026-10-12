#!/usr/bin/env python3
#
# Copyright 2026 the janino-querysim authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""The steps of the workflow "qsim" (.github/workflows/qsim.yml) that are more than one command.

Subcommands:

  coordinates <spec>
      Resolves a Janino version: "latest-3.1.x" is the last released 3.1.x version of io.github.janino-lts:janino
      on Maven Central, anything else is taken as the version. Prints "version=..." and "group=..." for
      $GITHUB_OUTPUT; versions up to 3.1.14 have the group ID org.codehaus.janino, later ones io.github.janino-lts.
  matrix --java 21,25 --replicas 6 --suites smoke,regression --modes xint,tiered --measurements P1,P8
         [--candidate latest-3.1.x] [--candidate-ref master] [--javac-compliance true|false]
      Checks the inputs and prints the matrix of the jobs for $GITHUB_OUTPUT. The compliance mode needs a candidate
      of the development line: a candidate-ref, or a candidate that is not a 3.1 version.
  suites --jar qsim.jar --suites smoke,regression[,campaign] --seeds 1..2000 [--campaign-options=...]
         [--javac-compliance true|false] --out <dir>
      Runs the correctness suites, each into <dir>/<suite>, and writes <dir>/status.tsv; the exit code is 1 if a
      suite fails. The campaign options are further options of "fuzz", e.g. "--try-jumps". With javac-compliance,
      the suites also run the candidate in its compliance mode ("--compliance").
  bench --jar qsim.jar --replica <name> --modes ... --measurements ... --forks 3 --rounds 40 [--points ...] --out <dir>
      Runs the measurements of one replica into <dir>/<replica> and writes <dir>/status.tsv.
  aggregate --jar qsim.jar --java 21,25 --artifacts <dir> --out <dir>
      Combines the raw data of all replicas, per JDK, into <out>/performance-java<N>, and writes
      <out>/status-performance.tsv; the exit code is 1 if an aggregation fails.
  summary --artifacts <dir> --reports <dir>
      Prints the job summary in Markdown: the inputs, the result of every suite and job, and the reports.

The summary reads the inputs of the run from the environment variables QSIM_<INPUT> (e.g. QSIM_CANDIDATE_REF).
"""

import argparse
import json
import os
import re
import shlex
import subprocess
import sys
import time
import urllib.request
import xml.etree.ElementTree as ElementTree
from pathlib import Path

METADATA_URL = "https://repo.maven.apache.org/maven2/io/github/janino-lts/janino/maven-metadata.xml"
JAVA_VERSIONS = ["21", "25"]
CORRECTNESS_SUITES = ["smoke", "regression", "campaign"]
SUITES = CORRECTNESS_SUITES + ["bench"]
MODES = ["xint", "c1", "tiered"]
MEASUREMENTS = ["P%d" % n for n in range(1, 11)]
MAX_REPLICAS = 20

# GitHub limits a job summary to 1 MiB; each report gets at most this much of it.
MAX_REPORT_BYTES = 100_000

# The inputs of the workflow, in the order of the summary.
INPUTS = ["baseline", "candidate", "candidate-ref", "javac-compliance", "suites", "java", "seeds", "campaign-options",
          "measurements", "points", "modes", "replicas", "forks", "rounds"]


def version_key(version):
    return tuple(int(n) for n in re.findall(r"\d+", version.split("-")[0]))


def group_of(version):
    return "org.codehaus.janino" if version_key(version) <= (3, 1, 14) else "io.github.janino-lts"


def latest_3_1_x(metadata):
    root = ElementTree.fromstring(metadata)
    versions = [v.text.strip() for v in root.iter("version")
                if re.fullmatch(r"3\.1\.\d+", v.text.strip())]
    if not versions:
        raise SystemExit("No 3.1.x release in " + METADATA_URL)
    return max(versions, key=version_key)


def split(value):
    return [s.strip() for s in value.split(",") if s.strip()]


def check_subset(name, values, allowed):
    unknown = [v for v in values if v not in allowed]
    if unknown or not values:
        raise SystemExit("Input %s: %s; allowed are %s" % (name, ", ".join(unknown) or "empty", ", ".join(allowed)))


def cmd_coordinates(args):
    spec = args.spec.strip()
    if spec == "latest-3.1.x":
        with urllib.request.urlopen(METADATA_URL, timeout=60) as response:
            version = latest_3_1_x(response.read())
    elif re.fullmatch(r"\d+\.\d+\.\d+(-SNAPSHOT)?", spec):
        version = spec
    else:
        raise SystemExit("Not a version: '%s' (e.g. 3.1.19 or latest-3.1.x)" % spec)
    print("version=" + version)
    print("group=" + group_of(version))


def is_true(value):
    return value.strip().lower() == "true"


def cmd_matrix(args):
    java = split(args.java)
    check_subset("java", java, JAVA_VERSIONS)
    suites = split(args.suites)
    check_subset("suites", suites, SUITES)
    bench = "bench" in suites
    if bench:
        check_subset("modes", split(args.modes), MODES)
        check_subset("measurements", split(args.measurements), MEASUREMENTS)
    if is_true(args.javac_compliance) and not args.candidate_ref.strip() and (
            args.candidate.strip() == "latest-3.1.x" or args.candidate.strip().startswith("3.1.")):
        raise SystemExit("Input javac-compliance: the compliance mode needs a candidate of the development line, "
                         "e.g. candidate-ref master; candidate is %s" % args.candidate)
    if not re.fullmatch(r"\d+", args.replicas.strip()) or not 1 <= int(args.replicas) <= MAX_REPLICAS:
        raise SystemExit("Input replicas: %s; allowed is 1 to %d" % (args.replicas, MAX_REPLICAS))
    print("java=" + json.dumps(java))
    print("replicas=" + json.dumps(list(range(1, int(args.replicas) + 1))))
    print("correctness=" + str(any(s in CORRECTNESS_SUITES for s in suites)).lower())
    print("bench=" + str(bench).lower())


def run(command):
    """Runs a command, its output to the console; returns the exit code and the duration in seconds."""
    print("$ " + " ".join(command), flush=True)
    started = time.monotonic()
    code = subprocess.call(command)
    return code, round(time.monotonic() - started)


def cmd_suites(args):
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    status = []
    for suite in split(args.suites):
        if suite not in CORRECTNESS_SUITES:
            continue
        if suite == "campaign":
            # Batches of 50 seeds: with all profiles, a batch of 200 takes about 20 minutes on a fast machine and
            # would come close to the limit of 30 minutes on a runner. Options of the input come later and win.
            command = ["java", "-jar", args.jar, "fuzz", "--seeds", args.seeds, "--styles", "all", "--profiles",
                       "all", "--negatives", "--batch", "50"] + shlex.split(args.campaign_options) + [
                          "--out", str(out / suite)]
        else:
            command = ["java", "-jar", args.jar, "check", "--suite", suite, "--out", str(out / suite)]
        if is_true(args.javac_compliance):
            command.append("--compliance")
        code, seconds = run(command)
        status.append((suite, code, seconds))
    (out / "status.tsv").write_text("".join("%s\t%d\t%d\n" % s for s in status), encoding="utf-8")
    return 1 if any(code != 0 for _, code, _ in status) else 0


def cmd_bench(args):
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    command = ["java", "-jar", args.jar, "bench", "--mode", args.modes, "--forks", args.forks, "--rounds", args.rounds,
               "--replica", args.replica, "--out", str(out)]
    if args.points.strip():
        command += ["--points", args.points.strip()]
    command += split(args.measurements)
    code, seconds = run(command)
    (out / "status.tsv").write_text("%s\t%d\t%d\n" % (args.replica, code, seconds), encoding="utf-8")
    return code


def cmd_aggregate(args):
    artifacts = Path(args.artifacts)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    status = []
    for java in split(args.java):
        replicas = sorted(p for p in artifacts.glob("bench-java%s-r*" % java) if p.is_dir())
        if not replicas:
            status.append((java, -1, 0, 0))
            continue
        command = ["java", "-jar", args.jar, "aggregate", "--out", str(out / ("performance-java" + java))]
        code, seconds = run(command + [str(p) for p in replicas])
        status.append((java, code, seconds, len(replicas)))
    (out / "status-performance.tsv").write_text("".join("%s\t%d\t%d\t%d\n" % s for s in status), encoding="utf-8")
    return 1 if any(code > 0 for _, code, _, _ in status) else 0


def read_status(file):
    if not file.is_file():
        return []
    return [line.split("\t") for line in file.read_text(encoding="utf-8").splitlines() if line.strip()]


def result(code):
    return {"0": "passed", "-1": "no data"}.get(code, "**FAILED** (exit %s)" % code)


def demote(markdown, suffix):
    """Moves every heading one level down, outside of code blocks, so that the reports nest in the summary; the
    first heading gets the suffix, e.g. the Java version."""
    lines = []
    fenced = False
    titled = False
    for line in markdown.splitlines():
        if line.startswith("```"):
            fenced = not fenced
        if not fenced and line.startswith("#"):
            line = "#" + line
            if not titled:
                line += suffix
                titled = True
        lines.append(line)
    return "\n".join(lines) + "\n"


def truncate(markdown, limit=MAX_REPORT_BYTES):
    """Cuts a report at a line boundary so that it fits into the summary."""
    data = markdown.encode("utf-8")
    if len(data) <= limit:
        return markdown
    cut = data[:limit].decode("utf-8", errors="ignore")
    cut = cut[:cut.rfind("\n") + 1]
    return cut + "\n*Truncated; the whole report is in the artifact `qsim-reports`.*\n"


def cmd_summary(args):
    artifacts = Path(args.artifacts)
    reports = Path(args.reports)
    out = []
    out.append("# qsim\n")
    out.append("| Input | Value |\n| --- | --- |")
    for name in INPUTS:
        value = os.environ.get("QSIM_" + name.upper().replace("-", "_"), "")
        out.append("| `%s` | %s |" % (name, "`%s`" % value if value else "(empty)"))
    for label, variable in [("Baseline", "QSIM_BASELINE_RESOLVED"), ("Candidate", "QSIM_CANDIDATE_RESOLVED"),
                            ("qsim commit", "GITHUB_SHA")]:
        if os.environ.get(variable):
            out.append("| %s | `%s` |" % (label, os.environ[variable]))
    out.append("")
    out.append("The seed of the measurements is 1; the same inputs measure the same units in the same order.\n")

    rows = []
    sections = []
    for directory in sorted(artifacts.glob("correctness-java*")):
        java = directory.name[len("correctness-java"):]
        for suite, code, seconds in read_status(directory / "status.tsv"):
            rows.append("| %s | %s | %s | %s s |" % (java, suite, result(code), seconds))
            report = directory / suite / "report-correctness.md"
            if report.is_file():
                sections.append((", Java " + java, report))
    for directory in sorted(artifacts.glob("bench-java*")):
        for replica, code, seconds in read_status(directory / "status.tsv"):
            rows.append("| %s | bench, replica `%s` | %s | %s s |"
                        % (directory.name.split("-")[1][len("java"):], replica, result(code), seconds))
    for java, code, seconds, replicas in read_status(reports / "status-performance.tsv"):
        rows.append("| %s | aggregate, %s replicas | %s | %s s |" % (java, replicas, result(code), seconds))
        report = reports / ("performance-java" + java) / "report-performance.md"
        if report.is_file():
            sections.append((", Java " + java, report))
    out.append("## Results\n")
    if rows:
        out.append("| Java | Suite or job | Result | Duration |\n| --- | --- | --- | ---: |")
        out.extend(rows)
    else:
        out.append("No suite ran.")
    out.append("")
    out.append("Durations per measurement and mode are in the section Environment of each performance report. "
               "Reports, raw data and reproducers are in the artifact `qsim-reports`.\n")

    for suffix, report in sections:
        out.append(truncate(demote(report.read_text(encoding="utf-8"), suffix)))
    sys.stdout.reconfigure(encoding="utf-8")
    print("\n".join(out))
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)

    p = commands.add_parser("coordinates")
    p.add_argument("spec")
    p.set_defaults(func=cmd_coordinates)

    p = commands.add_parser("matrix")
    p.add_argument("--java", required=True)
    p.add_argument("--replicas", required=True)
    p.add_argument("--suites", required=True)
    p.add_argument("--modes", default="xint")
    p.add_argument("--measurements", default="P1")
    p.add_argument("--candidate", default="latest-3.1.x")
    p.add_argument("--candidate-ref", default="")
    p.add_argument("--javac-compliance", default="false")
    p.set_defaults(func=cmd_matrix)

    p = commands.add_parser("suites")
    p.add_argument("--jar", required=True)
    p.add_argument("--suites", required=True)
    p.add_argument("--seeds", default="1..2000")
    p.add_argument("--campaign-options", default="")
    p.add_argument("--javac-compliance", default="false")
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_suites)

    p = commands.add_parser("bench")
    p.add_argument("--jar", required=True)
    p.add_argument("--replica", required=True)
    p.add_argument("--modes", required=True)
    p.add_argument("--measurements", required=True)
    p.add_argument("--forks", required=True)
    p.add_argument("--rounds", required=True)
    p.add_argument("--points", default="")
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_bench)

    p = commands.add_parser("aggregate")
    p.add_argument("--jar", required=True)
    p.add_argument("--java", required=True)
    p.add_argument("--artifacts", required=True)
    p.add_argument("--out", required=True)
    p.set_defaults(func=cmd_aggregate)

    p = commands.add_parser("summary")
    p.add_argument("--artifacts", required=True)
    p.add_argument("--reports", required=True)
    p.set_defaults(func=cmd_summary)

    args = parser.parse_args()
    sys.exit(args.func(args) or 0)


if __name__ == "__main__":
    main()
