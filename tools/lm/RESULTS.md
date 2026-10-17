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
