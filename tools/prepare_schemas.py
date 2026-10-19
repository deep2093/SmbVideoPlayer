"""Stage authoritative historical schemas for migration tests; fail on missing inputs."""
from pathlib import Path
import shutil
root = Path(__file__).resolve().parents[1]
target = root / "app/schemas/com.deepak.videolibrary.data.LibraryDatabase"
target.mkdir(parents=True, exist_ok=True)
for label, version in [("v1", 1), ("v2", 2), ("v3", 3), ("v4", 4)]:
    source = root / f"schema-history/schemas/com.deepak.videolibrary.history.{label}.LibraryDatabase/{version}.json"
    shutil.copyfile(source, target / f"{version}.json")
# The older v3 shape is tested through a helper with its historical database name.
source = root / "schema-history/schemas/com.deepak.videolibrary.history.v3early.LibraryDatabase/3.json"
extra = root / "app/schemas/com.deepak.videolibrary.history.v3early.LibraryDatabase"
extra.mkdir(parents=True, exist_ok=True)
shutil.copyfile(source, extra / "3.json")
print("Staged v1-v4 and early-v3 Room schemas")
