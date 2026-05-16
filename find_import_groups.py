import subprocess
import re
from collections import defaultdict

# Get all scala files
result = subprocess.run(['find', 'altitude/src', '-name', '*.scala'], capture_output=True, text=True)
files = result.stdout.strip().split('\n')

for f in sorted(files):
    with open(f) as fh:
        lines = fh.readlines()

    # Find import lines
    imports = []
    for i, line in enumerate(lines):
        if line.startswith('import '):
            # Extract package prefix (first two segments)
            m = re.match(r'import ([\w.]+)\.', line)
            if m:
                imports.append((i+1, m.group(1), line.strip()))

    if not imports:
        continue

    # Check for duplicated package prefixes in consecutive runs
    issues = []
    for j in range(len(imports)-1):
        if imports[j][1] == imports[j+1][1] and imports[j+1][0] == imports[j][0] + 1:
            issues.append(imports[j])

    if issues:
        print(f'=== {f} ===')
        for line_no, pkg, imp in imports:
            print(f'  L{line_no}: {imp}')
        print()

