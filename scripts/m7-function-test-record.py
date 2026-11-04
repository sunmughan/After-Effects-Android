#!/usr/bin/env python3
"""Build the M7 function-by-function test record from CI emulator JUnit XMLs.

Usage:
    python3 scripts/m7-function-test-record.py <xml-file-or-dir> [...]
        [--allow-failures]

Each JUnit testcase becomes one record row: test class, test method
(the function record's key), result (PASS / FAIL / ERROR / SKIPPED),
and crash evidence — the failure/error message plus its text, truncated
so one crashing test cannot flood the record. A testcase with no
failure/error/skipped child is a pass; a testcase absent from the XMLs
has no record at all and therefore counts as UNPROVEN (see
docs/M7_FUNCTION_COVERAGE.md), which this script never papers over:
it reports only what the XMLs contain.

Inputs are the test XMLs the android-ci workflow uploads as
`effectcraft-android-test-results` (app/build/test-results/ and
app/build/outputs/androidTest-results/connected/). The script reads
XML only; it runs no test, boots no emulator, and invents no result.

Exit code: 0 when every listed testcase passed (or was skipped),
1 when any testcase failed or errored, unless --allow-failures is
given (record still printed, exit 0). Skipped tests are reported as
SKIPPED, never as passes.
"""

import argparse
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

MAX_EVIDENCE_CHARS = 600


def iter_xml_files(inputs):
    for raw in inputs:
        path = Path(raw)
        if path.is_dir():
            yield from sorted(path.rglob("*.xml"))
        elif path.is_file():
            yield path
        else:
            print(f"warning: no such file or directory: {raw}", file=sys.stderr)


def parse_xml(path):
    try:
        root = ET.parse(path).getroot()
    except ET.ParseError as exc:
        print(f"warning: skipping unparseable XML {path}: {exc}", file=sys.stderr)
        return
    suites = [root] if root.tag == "testsuite" else root.iter("testsuite")
    for suite in suites:
        for case in suite.iter("testcase"):
            classname = case.get("classname", "")
            name = case.get("name", "")
            failure = case.find("failure")
            error = case.find("error")
            skipped = case.find("skipped")
            if error is not None:
                result, node = "ERROR", error
            elif failure is not None:
                result, node = "FAIL", failure
            elif skipped is not None:
                result, node = "SKIPPED", skipped
            else:
                result, node = "PASS", None
            evidence = ""
            if node is not None:
                message = (node.get("message") or "").strip()
                text = (node.text or "").strip()
                evidence = message if message else text
                if text and message and text != message:
                    evidence = f"{message} | {text}"
                evidence = evidence.replace("\n", " ")[:MAX_EVIDENCE_CHARS]
            yield classname, name, result, evidence


def main(argv=None):
    parser = argparse.ArgumentParser(description="M7 function test record from JUnit XMLs.")
    parser.add_argument("inputs", nargs="+", help="JUnit XML file(s) or directory(ies) containing them")
    parser.add_argument("--allow-failures", action="store_true",
                        help="Print the record but exit 0 even with failures/errors")
    args = parser.parse_args(argv)

    rows = []
    for path in iter_xml_files(args.inputs):
        rows.extend(parse_xml(path))
    # Deterministic order: class, then method. Duplicate testcase
    # entries (e.g. rerun shards) are kept, not deduplicated away:
    # a crash that appears in any shard is evidence.
    rows.sort(key=lambda row: (row[0], row[1], row[2]))

    total = len(rows)
    passed = sum(1 for row in rows if row[2] == "PASS")
    failed = sum(1 for row in rows if row[2] == "FAIL")
    errored = sum(1 for row in rows if row[2] == "ERROR")
    skipped = sum(1 for row in rows if row[2] == "SKIPPED")

    print("# M7 function test record (from CI emulator JUnit XMLs)")
    print()
    print(f"Total: {total} | PASS: {passed} | FAIL: {failed} | "
          f"ERROR: {errored} | SKIPPED: {skipped}")
    print()
    print("| Test class | Test (function) | Result | Crash / failure evidence |")
    print("|---|---|---|---|")
    for classname, name, result, evidence in rows:
        safe = evidence.replace("|", "\\|")
        print(f"| {classname} | {name} | {result} | {safe} |")
    print()
    if failed or errored:
        print(f"UNPROVEN/FAILED: {failed + errored} test(s) failed or errored. "
              "Their functions count as unproven until a green rerun replaces this record.")
    else:
        print("No failures or errors in the supplied XMLs. Functions absent "
              "from this table still count as unproven (see docs/M7_FUNCTION_COVERAGE.md).")
    if (failed or errored) and not args.allow_failures:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
