"""Expose generated schema files in CI logs so reviewers can retain them in source control."""
import json
from pathlib import Path
root = Path(__file__).resolve().parents[1]
files = {str(p.relative_to(root)): p.read_text() for p in sorted((root / 'app/schemas').rglob('*.json'))}
assert len(files) >= 6, 'Historical/current Room schemas are incomplete'
print('CODEX_SCHEMA_MANIFEST=' + json.dumps(files))
