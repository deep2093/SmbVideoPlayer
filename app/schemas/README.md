# Room schema history

Schema 5 adds physical-folder collections and the durable transfer journal. Schema export is mandatory.

Historical definitions live in the build-only `schema-history` module, with exact Git source references and an explicit reconstruction note for v1. Both shipped v3 shapes are tested. Build that module, run `python3 tools/prepare_schemas.py`, then build app instrumentation tests. Missing inputs fail rather than skipping migration validation.

Commit generated JSON files after validation. CI uploads them as Room-schemas and logs a schema manifest for review. Never hand-edit historical JSON to make a migration pass.
