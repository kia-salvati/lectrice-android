# Variants × cores: our Kokoro files and the "big cores only" option on both phones (2026-10-25)

Nothing committed. Build `sherpa1` (sherpa-onnx 1.13.8, core `v0.3.0-dirty`), release APK of 19:06, the same on both
phones. All runs over wireless adb, **unplugged**, app in front, screen on. Every bench = 3 runs of the 20-sentence
bench text, run 1 discarded (RTF = median of runs 2–3).

Models (research `../lectrice/research/2026-10-25-kokoro-variants.md`), each in its own folder, so the official int8
slot was never touched (SHA-256 checked in every result: int8 = `4b86207e…` = the official file on both phones):

| Name | Folder | Model SHA-256 |
|---|---|---|
| fp32 (official) | `kokoro-multi-lang-v1_0` | `b40f62b1…` |
| int8 (official) | `kokoro-int8-multi-lang-v1_0` | `4b86207e…` |
| Small (`w8`) | `kokoro-lectrice-w8-multi-lang-v1_0` | `9892dbe1…` |
| Fast (`q8`) | `kokoro-lectrice-q8-multi-lang-v1_0` | `d12189cf…` |

Results: `docs/results/S10plus-variants-cores.json`, `docs/results/S26U-variants-cores.json` (raw session files: ids,
counts, durations; no book text, no file names) and `docs/results/variants-cores-summary.json` (the tables below).

## What was built (spike only; nothing in the shared core's crates)

- `spike/src/main/kotlin/app/lectrice/spike/CpuAffinity.kt`: `CorePolicy` = `all` | `big` | an explicit CPU list
  (`6-7`). `big` = cores with ≥ 50 % of the fastest core's `cpu_capacity` (falls back to `cpuinfo_max_freq`).
  `sched_setaffinity` through JNA `Function` calls (R8 breaks a JNA `Library` interface) on the speech thread **before**
  the engine is created, so ONNX Runtime's workers inherit the mask. `ThreadCensus` lists the threads that appeared with
  the engine and their `Cpus_allowed_list` + last CPU.
- `Bench.loadEngine` pins first, then records `affinity` (policy, requested, applied, capacities) and every new thread's
  allowed CPUs; one log line per load: `cores big: asked 4-7, speech thread on 4-7; engine threads 4× 4-7`.
- adb: `--es cores all|big|<list>` for `engine`, `bench` and `soakWhenUnplugged` (default `all`); `bench` also takes
  `--es bundles fp32,small,fast,int8`. UI: an "all cores / big cores" chip row.
- `Small`/`Fast` are `testOnly` bundles: they load through the int8 slot's engine id, so **Play refuses them** (cached
  clips would mix with the official int8's).
- `spike/run-variants.sh <serial> <threads> <bundle:cores>…`: one bench per combination, `dumpsys meminfo` sampling,
  stops if the app leaves the front.
- Not done: `/proc/self/clear_refs` is denied to apps (`peakMemoryReset: false`), so `VmHWM` is the process's
  lifetime peak; the tables use `VmRSS` after the bench.

**Pinning worked on both phones**: the applied mask always equals the requested one, and every ONNX Runtime worker,
`speech-worker` and `export-runner` thread shows the pinned `Cpus_allowed_list` (S10+ `big`: all `4-7`). The only
thread outside the mask is a `Binder` pool thread (created by the system, not ours, idle during synthesis).

Core capacities: S10+ `253×4 (A55), 507×2 (A75), 682×2 (M4)` → `big` = `4-7`. S26 Ultra `741×6, 1024×2` → **`big` =
`0-7` = `all`** (no little cores), so "primes only" was tested as the explicit list `6-7`.

## Galaxy S10+ (Exynos 9820), 4 threads

Bench order as listed; the phone heated during the session (it was already at 35 °C and thermal status rose to 3 =
SEVERE), so later rows are a little disadvantaged.

| Model | Cores | RTF | First sentence | RSS | Battery °C | Thermal |
|---|---|---|---|---|---|---|
| fp32 | all | **1.413** | 4.35 s | 774 MB | 35.2 → 35.0 | 0 → 1 |
| fp32 | big | 1.442 | 4.37 s | 780 MB | 35.0 → 37.4 | 1 → 2 |
| Small | big | 1.509 | 4.70 s | 653 MB | 37.8 → 39.6 | 2 → 3 |
| Small | all | 1.546 | 4.77 s | 651 MB | 39.6 → 41.2 | 3 |
| **Fast** | all | **1.110** | **3.38 s** | 648 MB | 41.2 → 41.0 | 3 |
| Fast | big | 1.126 | 3.36 s | 648 MB | 41.0 → 39.9 | 3 |
| int8 | big | 2.615 | 8.03 s | 597 MB | 39.9 → 38.7 | 3 → 2 |

10-minute unplugged soak (60 s settle after the step; battery 47 % → 36 %):

| | fp32 4t all | Fast 4t big |
|---|---|---|
| Audio made in 10 min | 5.9 min | **9.0 min** |
| RTF minute 1 / last 2 min / median | 1.28 / 1.76 / 1.76 | 1.12 / 1.09 / **1.11** |
| Energy | 217.7 mAh = **36.9 mAh / audio min** | 193.9 mAh = **21.6 mAh / audio min** (−41 %) |
| Thermal status max, battery °C | 2 (MODERATE), 38.6 → 39.8 | 2, 39.7 → 40.2 |
| RSS max | 794 MB | 654 MB |

## Galaxy S26 Ultra (Snapdragon 8 Elite Gen 5 / SM8850)

This phone heats fast: the same fp32 4t run gave 0.538 at 29.7 °C and 0.739 at 38.7 °C (the `big` row, same mask
`0-7`), +37 % from heat alone. So the fair comparisons are the **cool-start rows** (battery < 35.5 °C at the start,
marked ❄), each after a cool-down.

| Model | Threads | Cores | RTF | First sentence | RSS | Battery °C | Thermal |
|---|---|---|---|---|---|---|---|
| fp32 ❄ | 4 | all | 0.538 | 1.54 s | 752 MB | 29.7 → 34.3 | 0 |
| Small | 4 | all | 0.639 | 1.97 s | 631 MB | 35.7 → 37.2 | 0 |
| Fast | 4 | all | 0.348 | 1.04 s | 626 MB | 37.2 → 38.2 | 0 → 1 |
| int8 | 4 | all | 0.968 | 2.90 s | 602 MB | 38.2 → 38.7 | 1 |
| fp32 | 4 | big (= 0-7) | 0.739 | 2.27 s | 769 MB | 38.7 → 39.9 | 1 |
| Fast | 2 | all | 0.447 | 1.39 s | 630 MB | 39.9 | 1 |
| Small | 2 | all | 0.788 | 2.44 s | 637 MB | 39.9 → 40.7 | 1 |
| fp32 | 2 | all | 0.777 | 2.41 s | 770 MB | 40.7 → 41.0 | 1 |
| fp32 | 2 | primes 6-7 | 0.778 | 2.41 s | 770 MB | 41.0 → 41.2 | 1 |
| Fast | 2 | primes 6-7 | 0.446 | 1.39 s | 634 MB | 41.2 → 41.1 | 1 |
| fp32 | 8 | all | 0.521 | 1.54 s | 773 MB | 41.1 → 42.7 | 1 → 2 |
| **Fast ❄** | 8 | all | **0.193** | **0.56 s** | 637 MB | 35.3 → 40.9 | 0 → 3 |
| Small | 8 | all | 0.482 | 1.38 s | 638 MB | 40.9 → 42.2 | 3 |
| fp32 ❄ | 8 | all | 0.455 | 1.35 s | 769 MB | 35.5 → 41.6 | 0 → 3 |
| Fast ❄ | 4 | all | 0.240 | 0.70 s | 632 MB | 34.0 → 39.1 | 0 → 2 |

Cool start, same temperature: **Fast is 2.2–2.4× faster than fp32** (8t 0.193 vs 0.455; 4t 0.240 vs 0.538). Small ≈
fp32 at equal heat (2t 0.788 vs 0.777). The official int8 is the slowest again.

10-minute unplugged soaks, each from 34.3 °C (battery 46 % → 41 % → 35 %):

| | fp32 4t all (today's default) | Fast 8t all |
|---|---|---|
| Audio made in 10 min | 14.7 min | **31.3 min** |
| RTF minute 1 / last 2 min / median | 0.59 / 0.75 / 0.70 | 0.19 / 0.38 / **0.34** |
| Energy | 195 mAh = **13.3 mAh / audio min** | 275 mAh = **8.8 mAh / audio min** (−34 %) |
| Thermal status max | 1 (LIGHT) | **3 (SEVERE) after 42 s**; battery up to 42.9 °C |
| RSS max | 780 MB | 652 MB |

fp32 4t sustained **0.75 here is worse than S5's 0.58** (fp32 8t): 4 threads isn't the right fp32 setting on this phone
either. Fast 8t gets hot quickly (like fp32 8t in S5), but even throttled it stays at ~2.7× real time.

## Findings

1. **"Big cores only" gives nothing measurable on either phone.** S10+: within ±3 % of `all` for every model (fp32
   1.442 vs 1.413, Fast 1.126 vs 1.110, Small 1.509 vs 1.546), the differences follow the heat, not the mask; with 4
   threads Android's scheduler already keeps the busy workers on the 4 big cores. S26 Ultra: no little cores, `big` =
   `all`; primes-only `6-7` at 2 threads = `all` (0.778 vs 0.777, 0.446 vs 0.447). Pinning itself works, and is cheap
   to keep for a phone where it might matter (e.g. 8 threads on a 4-big-core phone, which S7 found much slower).
2. **Fast (q8) wins everywhere it matters**: S10+ −21 % RTF in the bench and −37 % sustained (1.11 vs 1.76), −41 %
   energy per audio minute; S26 Ultra 2.2–2.4× faster cool, 2× sustained, −34 % energy; first sentence 0.56 s (S26) /
   3.4 s (S10+) vs 1.35 s / 4.35 s; ~125–140 MB less RAM than fp32. The S10+ is still slower than real time (1.11 > 0.9
   limit), so the S10+ class still needs Prepare-first, but it now prepares an hour of audio in ~67 min instead of
   ~105 min.
3. **Small (w8) = fp32 speed with ~125 MB less RAM** (within ~1–7 %, mostly heat), and **1.5–1.7× faster than the
   official int8** (S10+ 1.51 vs 2.62, S26 0.639 vs 0.968) at about the same size. It passes the research's "small"
   mark (within 10 % of fp32, memory clearly lower).
4. Heat dominates S26 numbers: compare only runs that start at the same temperature.

## Recommendation

- **Core policy: `all` on every phone class** (the default stays `all`). Keep `big` as an internal option, not a user
  setting. Instead, pick **threads per class**: S10+ class (2+2+4 cores) **4 threads**; S26 class (no little cores)
  **8 threads** for Fast (fp32 is also better at 8 than 4 there, S5).
- **Default model: Fast, if it passes the user's ear test** (not done yet: listen to a Fast clip against fp32 before
  deciding). It is the only change that moves the S10+ class toward real time, and it cuts battery use by a third or
  more on both phones. If the ear test fails: default fp32 on S26-class phones, fp32 + Prepare-first on S10+ class.
- **Choices for the user: "Fast" deserves to be a choice, and the official int8 should go.** Proposed list: **Fast**
  (default, ~111 MB), **Full quality** (fp32, ~326 MB), **Small** (w8, ~111 MB, fp32 sound at fp32 speed). Since Fast
  and Small are the same size, Small only makes sense if the ear test finds Fast's sound worse; otherwise ship two
  choices (Fast, Full quality). The official int8 is dominated by Small on every measure.
- Before shipping (from the variants research): own engine id + file name per variant in the core (cached clips must
  never mix), SHA-256 check before loading, NOTICE for the modified files.

## State left behind

- Official int8 untouched in its slot on both phones (`4b86207e…`); Small and Fast stay in their own folders.
- Phones: S10+ ~34 % (its wireless adb dropped at the end, after all its runs were done and pulled), S26 Ultra 35 %
  (43 °C after the last soak, spike app still open).
- No Gradle build was needed in this session (the installed APK already had the cores code), so no daemon to stop.
- Uncommitted files in `lectrice-android` (this and earlier steps): see `git status`.
