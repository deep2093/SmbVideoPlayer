# Historical Room fixtures

This build-only module regenerates schemas from historical entity definitions. It is never packaged into the app.

- v2: commit 297412d3d9729b17a8bd537fceb9a08073c2b8ed.
- v3early: commit 3bed915d4e898f6fcc25cc51a939167b5ba9a881 (before thumbError).
- v3: commit a2c52d89e26005d5c60e5b20af4dee84c05b4b4e.
- v4: commit 25036009897ba15f4041479228482493559560ce.
- v1 is reconstructed from v2 by reversing the documented MIGRATION_1_2 (remove customGroup and custom_folders); the uploaded Git history has no v1 source.

Only package names, schema export, and unused DAO declarations were changed. Both released v3 shapes must upgrade successfully. Run `./gradlew :schema-history:assembleDebug` and `python3 tools/prepare_schemas.py` before instrumentation packaging. Commit the generated schemas after validation.
