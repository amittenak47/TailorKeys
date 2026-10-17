# English next-word model results

Checkpoint step: 70000. Evaluation: row-quantized int8 weights dequantized in PyTorch.
All held-out sentences are evaluated.

Accuracy counts lexical words, including OOV targets as misses. Special tokens are excluded from suggestions. Known prefixes include a whole word shorter than two letters. No personal learning or corrections. Overall is a micro-average of all held-out lexical targets (not source-balanced).

The baseline reproduces the shipped empty-prefix trigram/bigram tiers, then count, logarithmic frequency/context score and lexical tie-break. Prefix results use exact completions ranked by the shipped score; fully typed words remain eligible. Previous lexical words reset at sentence boundaries. Existing n-gram assets may already contain held-out Tatoeba sentences, which favors the baseline.

| Source | Words | OOV | PPL | Prefix | LSTM top-1 | Baseline top-1 | LSTM top-3 | Baseline top-3 |
|---|---:|---:|---:|---|---:|---:|---:|---:|
| common_voice | 11,058 | 6.68% | 162.14 | 0 letters | 16.31% | 12.46% | 27.67% | 20.90% |
| common_voice | 11,058 | 6.68% | 162.14 | 1 letters | 39.90% | 32.20% | 57.48% | 49.44% |
| common_voice | 11,058 | 6.68% | 162.14 | 2 letters | 58.46% | 50.75% | 74.15% | 67.17% |
| tatoeba | 311,841 | 3.89% | 48.25 | 0 letters | 26.70% | 20.08% | 42.98% | 32.29% |
| tatoeba | 311,841 | 3.89% | 48.25 | 1 letters | 56.13% | 42.67% | 73.42% | 60.98% |
| tatoeba | 311,841 | 3.89% | 48.25 | 2 letters | 72.79% | 60.02% | 86.48% | 76.55% |
| soda | 2,363,055 | 1.28% | 24.38 | 0 letters | 34.44% | 16.69% | 50.76% | 28.37% |
| soda | 2,363,055 | 1.28% | 24.38 | 1 letters | 65.36% | 43.16% | 83.50% | 63.04% |
| soda | 2,363,055 | 1.28% | 24.38 | 2 letters | 80.65% | 62.08% | 92.76% | 81.00% |
| overall | 2,685,954 | 1.61% | 26.55 | 0 letters | 33.47% | 17.07% | 49.77% | 28.80% |
| overall | 2,685,954 | 1.61% | 26.55 | 1 letters | 64.19% | 43.06% | 82.22% | 62.74% |
| overall | 2,685,954 | 1.61% | 26.55 | 2 letters | 79.65% | 61.80% | 91.95% | 80.43% |

Perplexity counts current-sentence tokens including commas/numbers, excluding EOS and context warmup. Training early stopping uses a separate deterministic subset and source weighting. Normalized sentences are deduplicated globally before the hash split; no held-out sentence is used as training context.

Evaluation elapsed: 31.5 seconds. Full raw counts and provenance are saved outside the repository at `..\tk-lm-data\evaluation_int8.json`.

## Model and verification

Asset: **3,331,705 bytes** (3.33 MB), 3,069,816 parameters; V=15000, D=96, H=672. SHA-256: `1277eafdfb0ce4464acae11f61358e886065708e19050c06afde5cd49bb18919`.

Training path elapsed: 25.20 minutes, 70,000 updates; selected checkpoint 70,000. Budget: 75.0 minutes, with validation-driven LR halving and early stopping. Early-stopping perplexity includes EOS and uses source-balanced validation weights, unlike the lexical perplexity above.

Host JVM 23.0.1 (amd64), warmed medians: step **0.604 ms**, step + full softmax **1.016 ms**. Combined range across nine 100-call batches: 1.009–1.036 ms. Measured heap allocation: 0.00 bytes/call (counter overhead subtracted). This is a desktop measurement, not proof of <3 ms on a mid-range tablet.

Reference prompts: 12 exported from the float32 checkpoint; 21/40 candidates retained exact top-10 ordering with a >0.001-nat adjacent margin and <=0.12-nat quantization drift. Maximum observed top-10 drift across all candidate prompts: 0.1365 nats. JVM tests require exact IDs and <=0.15 nats on the exported prompts.

Swift mirrors the equations and format, uses Accelerate, and was manually reviewed. It cannot be compiled on this Windows host. Tablet/iOS latency remains unmeasured; nothing was installed on a connected device. Android debug assembly and JVM verification are recorded with the final commit.

Final verification: six pipeline tests passed (including CUDA CIFG output/gradient parity); all three JVM tests passed; `assembleDebug` passed with JDK 23 and `--no-daemon`. The APK's embedded model SHA-256 matches the exported asset and reference vectors. No integration files were changed.

Float32 vs int8 overall top-1: 33.4707% vs 33.4674% (-0.0034 percentage points); perplexity 26.5430 vs 26.5471.

Host CPU: Intel Core i7-14700K. Preparation used about 0.5 GiB observed working RAM; observed training host working set was about 1.7 GiB, with 3,042 MiB peak CUDA allocation. Balanced training data: 36,366,555 target tokens, 49.99% SODA. Full source hashes, counts, software versions, validation history and test metadata are in [provenance.json](provenance.json); corpus text and checkpoints remain outside git.

## Integrated keyboard

Measured 2026-10-17 with the actual JVM `Predictor.closest()` candidate builder,
split layout, correction budget and correction cap. This is a deterministic sample
of **768 held-out sentences / 6,946 lexical words**, with 256 sentences per source
(2,002 Common Voice, 1,913 Tatoeba and 3,031 SODA words). The overall row is a
micro-average of this sample, not the full-corpus mixture above. OOV targets remain
in the denominator. Personal dictionaries are empty; known letters have no spatial
tap observations. Separate tests cover real tap costs and personal learning.

The old scorer is the unchanged fallback, including its typo corrections and its
exclusion of the exact typed word. The active model keeps fully typed words eligible
(including `i`, `a`, `to`, etc.). The earlier Python baseline evaluated only exact
completions and kept fully typed words; its baseline numbers, especially at two
letters, are therefore not directly comparable to these real keyboard results.

| Source | Letters | Old top-1 | Combined top-1 | Model-only top-1 | Old top-3 | Combined top-3 | Model-only top-3 |
|---|---:|---:|---:|---:|---:|---:|---:|
| common_voice | 0 | 11.49% | 14.84% | 14.84% | 20.03% | 25.72% | 25.72% |
| common_voice | 1 | 29.22% | 38.51% | 38.51% | 44.76% | 55.84% | 55.84% |
| common_voice | 2 | 25.27% | 56.69% | 56.69% | 38.06% | 72.63% | 72.53% |
| tatoeba | 0 | 20.49% | 26.19% | 26.19% | 32.88% | 43.39% | 43.39% |
| tatoeba | 1 | 41.14% | 56.72% | 56.72% | 58.29% | 74.54% | 74.54% |
| tatoeba | 2 | 33.66% | 74.23% | 74.23% | 49.50% | 87.87% | 87.87% |
| soda | 0 | 17.02% | 34.64% | 34.64% | 27.78% | 50.91% | 50.91% |
| soda | 1 | 41.14% | 66.02% | 66.02% | 59.12% | 83.37% | 83.37% |
| soda | 2 | 33.92% | 81.13% | 81.13% | 51.60% | 93.07% | 93.07% |
| **overall** | **0** | **16.38%** | **26.61%** | **26.61%** | **26.95%** | **41.58%** | **41.58%** |
| **overall** | **1** | **37.71%** | **55.53%** | **55.53%** | **54.75%** | **73.01%** | **73.01%** |
| **overall** | **2** | **31.36%** | **72.19%** | **72.19%** | **47.12%** | **85.75%** | **85.72%** |

Combined scoring meets the model-only bar in all six overall checks (and in each
source): identical top-1, identical zero/one-letter top-3, and two extra two-letter
top-3 hits. Model-only means full-vocabulary lexical exact-prefix ranking using the
same cached distribution, without the dictionary prior, personal boosts or corrections.

Scoring uses `lp(word) + wordBoost + 2.25*ln(1+userPair) + 2.7*ln(1+userTriple) - 32*cost/10`.
`wordBoost` is the original personal frequency multiplier expressed
as a log ratio: `ln(1 + 0.05*userCount/baseFrequency)`. Seed n-grams and base frequency
do not affect in-vocabulary model scores. An OOV word receives
`lp(<unk>) + ln(0.1 * baseFrequency / dictionaryOovMass)` before personal boosts.
The remaining unknown mass is reserved for words outside the keyboard dictionary;
allocating all unknown mass to dictionary words overpromoted frequency-ranked words
such as `census` in conversation. Empty-prefix candidates are the model's top 300
lexical words plus learned pair/triple followers, without n-gram tiers.

Weights were calibrated on a **separate 768-sentence / 7,028-word** sample. Typo
weights 8, 32 and 48 were checked: 8 lost 34 model-only top-1 hits at two letters;
32 recovered them; 48 added no benefit. Reserving unknown mass removed the remaining
OOV-prior interference. Final calibration with weight 32 and OOV share 0.1 matched
model-only top-1 at all prefixes and added one two-letter top-3 hit. The final
evaluation above excludes every calibration record and fixes these weights.

Host JVM 23.0.1, Intel Core i7-14700K; median / p95:

| Operation | Median | p95 |
|---|---:|---:|
| Letter update + nextLetters + explore + closest (13,892 calls) | 1.717 ms | 3.430 ms |
| Committed token update: tokenization, incremental step, full softmax, top 300 (7,203 calls) | 1.193 ms | 2.259 ms |
| New sentence/field context rebuild (768 calls, mostly short/empty history) | 1.130 ms | 2.197 ms |

An unchanged-context cache check alone took **179 ns** median and **0.00 allocated
bytes/key** after warmup, with **zero model steps and zero softmax calls** while
typing letters. An explicit 81-token rebuild took **60.03 ms** median. All model
loading, tokenization, stepping, softmax and top-300 work runs on a serial background
worker in the keyboards; changed contexts immediately use legacy scoring and stale
results cannot be published. Two reusable score arrays are exchanged under a reader
lock. Weights are retained once per process. The 256 MiB JVM test heap was sufficient.
These timings exclude Android input-connection IPC and worker scheduling and do not
establish mobile latency.

Reproduction: set `JAVA_HOME` to `C:\Program Files\Java\jdk-23`, set `TK_LM_EVAL=1`,
and run `.\gradlew.bat --no-daemon testDebugUnitTest --quiet` after waiting for other
Gradle Java processes as documented in README.md. `TK_LM_EVAL_TUNE=1` selects the
calibration subset instead; the default selects 256 evenly spaced remaining records
per source. Original normalized tokens and their stored previous sentences are
rendered back to text and passed through `Predictor.updateContext()`, including the
runtime tokenizer. Reports and raw counts remain in `app/build/reports/`.

Provenance for the final run:

- Asset SHA-256 unchanged: `1277eafdfb0ce4464acae11f61358e886065708e19050c06afde5cd49bb18919`.
- Selected-record SHA-256 (UTF-8 `source:index:originalJsonLine\n`): `b067d563b6e5e7658ac409e66d8c732109bf7300f6b3abdbeb34a17323942377`.
- Calibration selected-record SHA-256: `1504be94a3a3f3352d76a1ebe38f3e36398b1551e1c224dd3cf10a016cc59350`.
- `common_voice.eval.jsonl.gz`: `6b2f0251ac8cf6436ba3da5cd637d04aae5928f5620fd4c2fe9f66ea3b3921d4` (1,390 records).
- `tatoeba.eval.jsonl.gz`: `efdd1084e66c6d42f35c281ffc01f711ab354bf75a6df1b6a9c83d90a83e24b4` (41,204 records).
- `soda.eval.jsonl.gz`: `baba1710dc89fe01d277c6f6f7c66e92186005e356e7da34d63e6bf0f3d96003` (208,534 records).
- Final integrated evaluation elapsed: 68.86 seconds.

Verification: 27 JVM tests passed, including the integrated accuracy assertions,
all 11 existing spatial tests, frozen fallback rankings, load failure, incremental
state, stale requests, score-buffer reuse, tokenization, personal/OOV learning,
special-token exclusion and non-English isolation. The optional historical spatial
benchmark was skipped. `assembleDebug` passed with JDK 23 and `--no-daemon`.
Swift mirrors the integration and was re-read for state,
buffer ownership, locking and callbacks; compilation and mobile latency still need
a Mac/device. No connected device was used or installed to.

### Corrected integrated results (typed word never offered back)

The keyboard never suggests the word that is already fully typed, so the model-only comparison now
excludes it too, and the combined scorer follows the same rule. Same 6,946 held-out words:

| Letters known | Old top-1 | New top-1 | Old top-3 | New top-3 |
|---|---:|---:|---:|---:|
| 0 | 16.4% | 26.6% | 27.0% | 41.6% |
| 1 | 37.7% | 52.2% | 54.8% | 68.9% |
| 2 | 31.4% | 53.2% | 47.1% | 64.8% |

Host medians: 1.27 ms per keystroke refresh, 1.16 ms per word boundary. The context read was cut
from 16,384 to 2,048 characters (the model only uses the last two sentences plus the current one).
