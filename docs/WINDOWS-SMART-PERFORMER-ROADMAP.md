# SkyWing Smart Performer Detection: Windows-first roadmap

**Status:** Approved design proposal, not implemented.
**Platform order:** Windows x64 (Windows 10/11), then Android after benchmark and user acceptance.
**Base:** existing `feature/windows-x64-foundation` branch. Do not change Android implementation or remove its legacy grouping rules.
**Goal:** During SMB/local video indexing, detect adult-performer names *anywhere* within filenames, including dotted, underscored, compact/concatenated, unusual one-word stage names, and verified aliases; automatically create accurate virtual folders and fetch portraits only after match confidence is sufficient.

## Decision: catalogue acquisition
- StashDB is the primary provider. Official Stash-Box GraphQL schema exposes `queryPerformers(input: PerformerQueryInput!)`, paged results, `count`, `name`, `aliases`, `id`, `disambiguation`, and `images`. StashDB access uses the user's API key.
- ThePornDB is a second Stash-Box provider with its own GraphQL endpoint and API credentials. Make integrations optional and independently switchable.
- Stash (local self-hosted media manager) JSON export or a user-created CSV/JSON can seed or override the local catalogue.
- **Important:** technical pagination is not permission for bulk export, mirroring, offline redistribution, packaging records in app installers, or large-scale querying. Before any full-catalogue sync, verify provider terms, API usage limits, licensing and permission for that use. Unless approved, use on-demand lookup plus a growing *local user-specific* cache and optional user-supplied/authorised import instead of scanning the entire remote service.
- Do not bundle private tokens in source control, installers, logs or exports. Use Windows Credential Manager/DPAPI and a clear sign-out/revoke mechanism.
- Do not download all portraits; lazy-load after confirmation under provider terms.

## Phases and acceptance gates
| ID | Milestone | Scope | Done when |
|---|---|---|---|
| P0 | Source and contract spike | Verify live GraphQL fields, pagination, auth, terms, rate limits; test ThePornDB separately and confirm approved scale | Document tested queries and permitted sync strategy; user can connect and disconnect |
| P1 | Provider-independent models | Pure Kotlin JVM interfaces `PerformerProvider`, `PerformerRecord`, `Alias`, `Candidate`, stable scoped IDs; migration-friendly storage | Two mocked providers pass adapter tests; duplicate names do not collapse different people |
| P2 | Local index | SQLite performer, alias, provider identity, sync checkpoint, updated time and lookup indexes; import CSV/JSON; cache on-demand results | Offline searches succeed for cached data; updates are idempotent, incremental and resumable |
| P3 | Filename matching | Unicode normalisation, separators, full-filename token search, exact names, aliases, compact matching, cautious fuzzy matching | Regression corpus covers unusual names, no-space names, spelling variants, multiple performers |
| P4 | Virtual folder integration | Person IDs underpin virtual folders, multiple persons per video, background incremental scan, change diff, reversibility | No SMB writes; re-scan leaves manual assignments/covers/history intact; no duplicate virtual folders |
| P5 | Artwork pipeline | Lazy portrait fetch using trusted performer ID, provider precedence, thumbnail fallback, opt-out; cache/expiry | Correct portrait or thumbnail fallback; ambiguous matches never silently attach wrong image |
| P6 | Review and learning | Match review page, reject/confirm, alias mapping, ignore rules, merge/split, UI explanations, per-library settings | Corrections persist across re-scans and provider refreshes; false positives are reversible |
| P7 | Windows evaluation | Labeled test corpus (at least 500 filenames, including mixed/ambiguous examples), speed/memory tests, offline, error handling, provider sync | Target >=98% precision on automatically created people folders and >=90% top-3 suggestion recall, measured on held-out corpus; no severe regressions |
| P8 | Android port | Extract platform-neutral Kotlin detection/index logic; Android storage/WorkManager adapters; reuse existing artwork integration | Android test corpus produces equivalent match decisions; existing Android folders/history/settings preserved |

**Schedule:** P0-P2 approximately 1-2 weeks, P3-P6 approximately 2-4 weeks, P7 about 1 week, P8 about 1-3 weeks after Windows acceptance. Estimates are rough and exclude acquiring provider approval or creating catalogues from scratch. Can be developed alongside Windows W1-W3 once the Windows app can browse filenames.

## Detection algorithm (offline-first)
1. Strip extension and release artifacts (`1080p`, `HEVC`, `WEB-DL`) without removing performer digits or their meaningful tokens.
2. Normalise Unicode case and diacritics for comparisons; retain the original filename separately. Map dots, underscores and common punctuation to token boundaries. Do not overwrite files.
3. Build indexes from canonical names AND verified aliases. Maintain token-sequence index/trie or Aho-Corasick automaton, plus a compact identifier index (removing separators) for names embedded in strings. Restrict compact matches to plausible word/segment boundaries or strong contextual evidence; no naïve one-word substring search.
4. Score candidates: exact consecutive token name > verified alias > compact name > bounded edit-distance fuzzy. Context words (with, starring, featuring), repeated appearances, uniqueness and provider IDs can increase confidence; common-language tokens, short names and collisions reduce it.
5. Search all valid sequences anywhere in filenames. Prefer full multiword matches over overlapping one-word matches. Allow different performers for disjoint spans.
6. Only auto-create folder for unambiguous exact/verified alias identity. Candidate fuzzy spelling (e.g. `melody.mark` -> `Melody Marks`) is a *suggestion* until user confirmation unless evaluation proves a conservative automated threshold is safe.
7. Keep low-confidence or unknown items in Original/Unmatched; log local confidence reasons without exposing full paths to providers.
8. Fetch portrait metadata by confirmed scoped provider ID, respect image rights and stored crop choice; video frame fallback on network failure.
9. Incremental cache means repeated scans are fast, and rejected matches stay rejected. Use bounded async workers with cancellation/back-off.

### Example cases and intended behavior
| Filename | Intended classification |
|---|---|
| `hanging.out.with.melody.marks.during.winter.break.mp4` | Exact Melody Marks if present and unique in verified index |
| `hanging.out.with.melody.mark.during.winter.brake.mp4` | Suggest Melody Marks; await confirmation for fuzzy spelling |
| `MelodyMarks-Hangout.mkv` | Compact exact match if supported by catalogue and safe segment boundary |
| `cassie2sassi.scene.mp4` | Exact single-token unusual stage name (number preserved) |
| `melztube.scene.mp4` | Match only if known performer/alias; avoid assigning uploader/studio names as people |
| `melody.ellie.nove.mp4` | Longest matching verified multiword names first; do not guess segmentation into 3 people |
| `scene.with.a.john.mp4` | Ambiguous/common/short names go to Review, not auto-created |
| `tom.cruise,brad.pitt.scene.mp4` | Two independently identified people if identities exist in catalogue |

### Suggested database schema
- `performers(provider_id, provider_performer_id, display_name, disambiguation, image_url, updated_at, deleted_at)` PRIMARY KEY(provider_id, provider_performer_id)
- `names(provider_id, provider_performer_id, name, normalized_name, compact_name, name_type, verified, updated_at)`
- `video_match(library_id, video_id, provider_id, performer_id, confidence, match_kind, confirmed_by_user, source_text_range, last_scan_fingerprint)`
- `match_override(library_id, normalized_phrase, action, provider_id, performer_id, created_at)` supporting confirm, ignore, reject, and custom alias; use scoped keys
- `catalog_sync(provider_id, cursor_or_page, last_success, schema_version, enabled, sync_policy)`
- Index normalized/compact names and a lightweight FTS lookup; keep artwork blobs in a distinct bounded cache.
- Avoid storing real names/identifying sensitive performer metadata unless strictly necessary; names, aliases and provider IDs are sufficient for initial matching.

### Desktop UI
- Library > Virtual Folders > `Performers` with portrait/thumbnail choice and a `Needs Review` queue.
- Settings > Library > Smart Performer Detection: OFF/ON; Provider Connections; Offline Catalogue Status; Confidence (Conservative default); Review Matches; Ignore/Custom Aliases; Artwork; Sync/Import/Export; Rebuild Index.
- Keep the legacy grouping choice available through Windows experimentation, and *do not modify/remove any Android grouping settings* until Android port acceptance.
- Preview changes before applying on existing collections; no renaming/deleting physical SMB files.

### Performance, privacy, and tests
- Aim to match 10,000 filenames against a 100,000+ record test catalogue without quadratic brute force. Use precompiled indexes and shortlist fuzzy comparisons rather than all-pairs edit distance. Measure memory, startup and re-scan latency on actual Windows x64 hardware.
- Never send full SMB paths or filenames to third-party services without opt-in. Look up only minimal candidate names with a privacy warning and per-provider settings.
- Create benchmark fixtures including common names, titles, sites, multiple names, periods, underscores, camelCase, typos, unusual digits, diacritics and overlapping aliases. Use synthetic fixtures and only properly authorised source content.
- No web search for every filename. No forced changes to playback engine, HDR, debrid, Android code or Android build.
- Track privacy/terms compliance of provider APIs, images and data retention before any publicly redistributed builds.

### Suggested next practical action
Implement **P0 as a small Windows-only proof**: connect a user-provided StashDB API key, query 1-2 pages with `queryPerformers`, verify `aliases`, count, schema and legal bulk limits, cache a small set in SQLite, and run a console matcher on the ten filename examples above. Do not initiate a full download without checking permissions and rate limits.

## References
- https://github.com/stashapp/stash-box/blob/master/graphql/schema/schema.graphql
- https://github.com/stashapp/stash-box/blob/master/graphql/schema/types/performer.graphql
- https://guidelines.stashdb.org/docs/faq_getting-started/stashdb/accessing-stashdb/
- https://guidelines.stashdb.org/docs/faq_getting-started/stashdb/accessing-stash-boxes/
- https://docs.stashapp.cc/in-app-manual/tasks/jsonspec/
