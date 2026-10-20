"""Retain measured app rules; dependencies supply their own baseline profiles."""
import json
from pathlib import Path
# includeInStartupProfile uses the startup-prof filename on Benchmark 1.3.
rules = set()
for path in Path('benchmark-output').rglob('*-prof*.txt'):
    rules.update(line for line in path.read_text().splitlines() if 'Lcom/deepak/videolibrary/' in line)
assert rules, 'No measured app baseline profile was exported'
print('CODEX_BASELINE_PROFILE=' + json.dumps('\n'.join(sorted(rules)) + '\n'))
