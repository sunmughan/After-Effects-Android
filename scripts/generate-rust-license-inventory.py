import json
import sys

data = json.load(sys.stdin)
packages = sorted(data.get("packages", []), key=lambda p: (p.get("name", ""), p.get("version", "")))
print("# Resolved Rust dependencies and declared licenses")
print()
print(f"Total resolved Cargo packages: {len(packages)}")
print()
print("| Package | Version | Declared license | Source |")
print("|---|---|---|---|")
for package in packages:
    name = package.get("name", "unknown").replace("|", "\\|")
    version = package.get("version", "unknown")
    license_id = (package.get("license") or "NOT DECLARED").replace("|", "\\|")
    source = (package.get("source") or "local/path").replace("|", "\\|")
    print(f"| {name} | {version} | {license_id} | {source} |")
