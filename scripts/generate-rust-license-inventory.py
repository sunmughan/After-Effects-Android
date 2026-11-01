import json
import sys

data = json.load(sys.stdin)
packages = sorted(data.get("packages", []), key=lambda p: (p.get("name", ""), p.get("version", "")))
third_party = [package for package in packages if package.get("source")]
missing = [
    f"{package.get('name', 'unknown')} {package.get('version', 'unknown')}"
    for package in third_party
    if not package.get("license")
]

print("# Resolved Rust dependencies and declared licenses")
print()
print(f"Total resolved Cargo packages: {len(packages)}")
print(f"Third-party packages: {len(third_party)}")
print()
print("> The local Android bridge is first-party project code. Its distribution license is intentionally not inferred from the upstream EffectCraft dependency; the repository owner must select and publish the Android repository's own license before external distribution.")
print()
print("| Package | Version | Declared license | Source |")
print("|---|---|---|---|")
for package in packages:
    name = package.get("name", "unknown").replace("|", "\\|")
    version = package.get("version", "unknown")
    if package.get("source"):
        license_id = (package.get("license") or "MISSING THIRD-PARTY LICENSE").replace("|", "\\|")
        source = package.get("source", "unknown").replace("|", "\\|")
    else:
        license_id = "FIRST-PARTY; repository license not selected"
        source = "local project source"
    print(f"| {name} | {version} | {license_id} | {source} |")

if missing:
    print()
    print("## ERROR: third-party packages missing declared license metadata")
    for package in missing:
        print(f"- {package}")
    sys.exit(1)
