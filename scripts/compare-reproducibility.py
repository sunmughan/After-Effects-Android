#!/usr/bin/env python3
"""Compare two debug APK builds for reproducibility (M0 criterion #1).

Usage:
    compare-reproducibility.py <first-apk> <second-apk> <report-path>

Compares three levels, in decreasing strictness:

1. RAW_APK_MATCH: SHA-256 of the two APK files as stored. This is the
   strictest level, but it is expected to differ across fresh runners
   even for an identical build, because a debug APK is signed with the
   runner's ephemeral debug keystore (~/.android/debug.keystore), which
   is generated per runner with fresh key material; the signature
   lives in the APK Signature Scheme v2 block, not in zip entries. A
   raw mismatch alone therefore does NOT fail this check; it is
   reported verbatim.
2. CONTENT_MATCH: per-entry SHA-256 of the decompressed contents of
   every APK entry except v1 signature files (META-INF/MANIFEST.MF
   and META-INF/*.SF, *.RSA, *.DSA, *.EC). Entry names, entry sets and
   contents must all match. Zip container metadata (entry order,
   timestamps, compression level, alignment padding) is intentionally
   ignored here because it is not build output content. A content
   mismatch fails the check.
3. NATIVE_SO_MATCH: the native .so entries PACKAGED INSIDE each APK
   (lib/<abi>/libeffectcraft_android.so, first APK versus second APK).
   This must be packaged-versus-packaged, never raw jniLibs files
   versus packaged entries: AGP strips native libraries while
   packaging (the packaged .so carries no .symtab), so a raw
   app/src/main/jniLibs .so is a different artifact from the .so that
   ships in the APK, and comparing them is an apples-to-oranges
   error (it falsely failed run #345 even though every packaged
   entry, including both packaged .so files, was byte-identical).
   A packaged-versus-packaged mismatch fails the check.

Limitation, stated not hidden: equality of the UNSTRIPPED
intermediate jniLibs .so files across builds is NOT proven here,
because the first build's jniLibs files are not uploaded as an
artifact. What is proven is equality of everything that ships in
the APK, which is what criterion #1 governs.

Exit code is 0 only when CONTENT_MATCH and NATIVE_SO_MATCH are both
yes. The report is written even on failure so a failing run still
publishes its evidence.
"""

import hashlib
import sys
import zipfile
from pathlib import Path

SIGNATURE_SUFFIXES = (".SF", ".RSA", ".DSA", ".EC")
ABIS = ("arm64-v8a", "x86_64")
SO_NAME = "libeffectcraft_android.so"


def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_bytes(data):
    return hashlib.sha256(data).hexdigest()


def is_signature_entry(name):
    if name == "META-INF/MANIFEST.MF":
        return True
    return name.startswith("META-INF/") and name.upper().endswith(SIGNATURE_SUFFIXES)


def entry_hashes(apk_path):
    """Map entry name -> SHA-256 of its decompressed content."""
    result = {}
    with zipfile.ZipFile(apk_path) as archive:
        for info in archive.infolist():
            result[info.filename] = sha256_bytes(archive.read(info.filename))
    return result


def main(argv):
    if len(argv) != 4:
        print(__doc__, file=sys.stderr)
        return 2
    first_apk = Path(argv[1])
    second_apk = Path(argv[2])
    report_path = Path(argv[3])

    first_raw = sha256_file(first_apk)
    second_raw = sha256_file(second_apk)
    raw_match = first_raw == second_raw

    first_entries = entry_hashes(first_apk)
    second_entries = entry_hashes(second_apk)
    first_content = {k: v for k, v in first_entries.items() if not is_signature_entry(k)}
    second_content = {k: v for k, v in second_entries.items() if not is_signature_entry(k)}
    first_signatures = sorted(k for k in first_entries if is_signature_entry(k))
    second_signatures = sorted(k for k in second_entries if is_signature_entry(k))

    content_diffs = []
    for name in sorted(set(first_content) | set(second_content)):
        if name not in first_content:
            content_diffs.append(f"only in second APK: {name}")
        elif name not in second_content:
            content_diffs.append(f"only in first APK: {name}")
        elif first_content[name] != second_content[name]:
            content_diffs.append(f"content differs: {name}")
    content_match = not content_diffs

    so_lines = []
    so_match = True
    for abi in ABIS:
        packaged = f"lib/{abi}/{SO_NAME}"
        if packaged not in first_entries:
            so_lines.append(f"{abi}: MISSING packaged entry {packaged} in first APK")
            so_match = False
            continue
        if packaged not in second_entries:
            so_lines.append(f"{abi}: MISSING packaged entry {packaged} in second APK")
            so_match = False
            continue
        first_hash = first_entries[packaged]
        second_hash = second_entries[packaged]
        match = first_hash == second_hash
        so_match = so_match and match
        so_lines.append(
            f"{abi}: packaged-in-first-apk={first_hash} packaged-in-second-apk={second_hash} match={str(match).lower()}"
        )

    lines = []
    lines.append("EffectCraft Android reproducibility comparison")
    lines.append(f"first APK:  {first_apk} sha256={first_raw}")
    lines.append(f"second APK: {second_apk} sha256={second_raw}")
    lines.append(f"RAW_APK_MATCH: {str(raw_match).lower()}")
    lines.append(
        "NOTE: a raw mismatch alone is not a build-content failure: debug APKs "
        "are signed with the runner's ephemeral debug keystore, generated per "
        "fresh runner. See CONTENT_MATCH and NATIVE_SO_MATCH for the build-output verdict."
    )
    lines.append(f"first APK entries: {len(first_entries)} total, "
                 f"{len(first_content)} content, {len(first_signatures)} signature")
    lines.append(f"second APK entries: {len(second_entries)} total, "
                 f"{len(second_content)} content, {len(second_signatures)} signature")
    lines.append(f"first APK signature entries: {', '.join(first_signatures) or '(none)'}")
    lines.append(f"second APK signature entries: {', '.join(second_signatures) or '(none)'}")
    lines.append(f"CONTENT_MATCH: {str(content_match).lower()}")
    if content_diffs:
        lines.append("content differences:")
        lines.extend(f"  - {diff}" for diff in content_diffs)
    else:
        lines.append("content differences: (none)")
    lines.append(f"NATIVE_SO_MATCH: {str(so_match).lower()}")
    lines.extend(f"  - {line}" for line in so_lines)
    verdict = "PASS" if (content_match and so_match) else "FAIL"
    lines.append(f"VERDICT (content + native .so): {verdict}")

    report = "\n".join(lines) + "\n"
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(report, encoding="utf-8")
    print(report, end="")
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
