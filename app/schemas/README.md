# Room schema history

Room writes one JSON file per database version here during the build
(`com.deepak.videolibrary.data.LibraryDatabase/<version>.json`).
Commit each new file whenever `@Database(version = ...)` changes, and never edit or
delete an old one: `MigrationTest` uses them to prove that upgrades keep the library.

Schema 4 is written by the first build after this change; commit it.
