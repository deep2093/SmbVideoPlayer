# The owner's PotPlayer 2× script, and SkyWing's PotPlayer-like experiment

Plan 5.7 item 1. This file archives the AviSynth script the owner's PotPlayer uses for 2× motion. PotPlayer keeps it
in its own settings, `AvisynthScript` under `HKCU\Software\DAUM\PotPlayerMini64\Settings`. It names no file or folder
of the owner's: `#SCRIPTDIR` is PotPlayer's own folder. Read on 24 November 2026.

```
AddAutoloadDir("#SCRIPTDIR/plugins")
SetFilterMTMode("DEFAULT_MT_MODE", 2)
SetMemoryMax(1024)

super=MSuper(pel=1, hpad=0, vpad=0)
backward_1=MAnalyse(super, chroma=false, isb=true, blksize=32, blksizev=32, searchparam=3, plevel=0, search=3, badrange=(-24))
forward_1=MAnalyse(super, chroma=false, isb=false, blksize=32, blksizev=32, searchparam=3, plevel=0, search=3, badrange=(-24))
backward_2 = MRecalculate(super, chroma=false, backward_1, blksize=8, blksizev=8, searchparam=0, search=3)
forward_2 = MRecalculate(super, chroma=false, forward_1, blksize=8, blksizev=8, searchparam=0, search=3)
MBlockFps(super, backward_2, forward_2, num=FramerateNumerator(last)*2, den=FramerateDenominator(last)*1, mode=2)

Prefetch(1)
```

The script uses MVTools 2 (MSuper, MAnalyse, MRecalculate, MBlockFps). SkyWing does not ship MVTools. It ships
open-svpflow, a reimplementation of SVPflow, and SVPflow's analysis is descended from MVTools. So SkyWing can only
come near this script; it cannot run it. The hidden experiment is `PotPlayer-like` (`MotionExperiment.POTPLAYER_LIKE`).
The benchmark picks it with `--experiment PotPlayer-like`. Nothing in Settings picks it, and it is never saved.

## How each setting maps

The MVTools meanings come from the MVTools 2 documentation, version 2.7.49 (`Documentation/mvtools2.html` in
pinterf/mvtools). The open-svpflow meanings come from its source: `crates/svpflow1/src/analyse/params.rs`, `field.rs`
and `super_opts.rs`. That source is a local checkout without git metadata. Its defaults match the ones read earlier
from the pinned deadbad2 revision, but the checkout's own revision is not confirmed.

| PotPlayer (MVTools) | What it does | PotPlayer-like (open-svpflow) |
|---|---|---|
| `MSuper(pel=1)` | Whole-pixel motion, no subpixel planes | Super `{gpu:1,pel:1}`, as in Fast |
| `hpad=0, vpad=0` | No border padding (default 8) | **Not available**: SVSuper has no padding option |
| `blksize=32, blksizev=32` (overlap default 0) | 32 × 32 blocks, no overlap | `block:{w:32,h:32,overlap:0}` |
| `search=3` | Exhaustive search at the finest level | Type 4, open-svpflow's default and also exhaustive. Its numbering is not MVTools': 2 is hexagon, 3 is UMH, and anything else is exhaustive |
| `pelsearch` (default: pel, so 1) | Radius at the finest level | `main.search.distance:1` |
| `search_coarse` (default 3) | Exhaustive search at the coarser levels | `main.search.coarse.type` 4 (default; exhaustive) |
| `searchparam=3` | Radius at the coarser levels | `main.search.coarse.distance:3` |
| `dct` (default 0) | SAD cost | `main.search.coarse.satd:false` (open-svpflow uses SATD there by default); the finest level is SAD by default |
| Only the finest level uses `search` and `pelsearch` | | `main.search.coarse.width:0`. Otherwise open-svpflow also searches every level at least 1050 pixels wide the finest-level way, so two levels at 4K |
| `plevel=0` (default 1 with truemotion) | No per-level penalty scaling | `main.penalty.plevel:0`. open-svpflow scales by plevel to the power of the levels below and reads 0.01 or less as 1.0: no scaling |
| `badrange=-24` | | `main.search.coarse.bad.range` -24 (default) |
| `isb=true` and `isb=false` | Vectors in both directions | `vectors` 3 (default) |
| Penalties (`lambda`, `lsad`, `pnew` at truemotion defaults) | | open-svpflow's defaults. Its penalty scales differ, so they are not mapped |
| `chroma=false` | Luma-only block cost | **Not available**: open-svpflow always adds chroma to the cost (plan 5.7 item 2) |
| `MRecalculate(blksize=8, searchparam=0, search=3)` | Refines the 32 × 32 vectors to 8 × 8 in one step | `refine:[{thsad:200},{thsad:200}]`. open-svpflow halves the block at each refine, so it goes 32 → 16 → 8: one step more than PotPlayer. Its refine radius is at least 1. MRecalculate's own defaults (`thSAD`, `overlap`) were not read in the documentation |
| `MBlockFps(..., mode=2)`, 2× | Block-based frame doubling | SmoothFps `algo:2,mask:{area:0}`, the renderer Fastest uses. It is the nearest renderer, not an equivalent. MBlockFps's modes were not read in the documentation |
| `SetFilterMTMode(..., 2)`, `Prefetch(1)`, `SetMemoryMax(1024)` | AviSynth+ threading and memory | **Not mapped**: VapourSynth uses all the processor's threads |

`Prefetch(1)` probably matters most for the comparison. AviSynth+ then fetches frames on one thread, which could
explain much of PotPlayer's 1.3 cores against SkyWing Balanced's 2.3 cores (plan 5.7). This is not yet measured.
To measure it, run PotPlayer with `Prefetch(2)` or more and compare its cores, or run SkyWing with VapourSynth
limited to one thread (not built).

## Numbers

`MotionNativeTest.eachOptionSetsCostIsMeasuredInRealTime` measures them. It writes its rows to
`windows/build/reports/tests/test/motion-cost.txt`. Each run plays a generated 24 fps pattern in real time at 2×, with
no display and no audio, on the OpenCL device SkyWing chooses. It counts the processor cores the whole process used
over 20 s, after 5 s of warm-up. The GPU's own time is not counted.

Laptop (Core Ultra 9 275HX, OpenCL on the RTX 5070 Ti Laptop GPU, driver 621.24), 24 November 2026. Every run held
48 output frames a second.

| Source | Fast | Balanced | PotPlayer-like |
|---|---|---|---|
| 1080p | 0.35 cores | 0.47 cores | 0.39 cores |
| 4K | 0.97 cores | 1.58 cores | 1.08 cores |

PotPlayer-like costs a little more than Fast here. It differs from Fast in several ways at once (the search, two
refine steps, no overlap, the renderer), so these runs don't say which one costs what. A pattern is not film, so only the differences between columns mean anything. The comparison that counts is
the owner's: the same clip in PotPlayer and in `bench.ps1 -Quality Fast -Experiment PotPlayer-like`.
