# English language-model sources and attribution

Only these three corpora supply training/evaluation text. No audio, narratives,
commonsense triples, external web text or pretrained weights are used.

| Source | URL | License | Attribution |
|---|---|---|---|
| Common Voice English sentence text | https://github.com/common-voice/common-voice/tree/main/server/data/en | [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) | Common Voice contributors / Mozilla, English sentence collection. Courtesy credit; CC0 does not require attribution. |
| Tatoeba English sentences | https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2 | [CC BY 2.0 France](https://creativecommons.org/licenses/by/2.0/fr/) | English sentences by Tatoeba contributors, https://tatoeba.org/, licensed under CC BY 2.0 FR. |
| SODA, allenai/soda | https://huggingface.co/datasets/allenai/soda | [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) | SODA, Kim et al. (2023), Allen Institute for AI. *SODA: Million-scale Dialogue Distillation with Social Commonsense Contextualization*. https://arxiv.org/abs/2212.10465. Licensed under CC BY 4.0. |

Common Voice is pinned to commit
`b99fe2a29245b527a3bdf18e973f09688ea46ce8`. Only `.txt` files in
`server/data/en/` are read. `europarl-*` is always excluded; `wiki*` is also
excluded conservatively to honor the no-Wikipedia restriction. Directory metadata
is used to discover files; no other part of the repository supplies training text.

SODA is pinned to revision `fdc848ab0183208ea7808206c91c724414d0a071`.
Only the `dialogue` column of `train.parquet`, `valid.parquet` and `test.parquet`
is read. The original split labels are replaced by the shared deterministic
sentence-hash holdout. Dialogue turns are split into sentences; at most two
preceding sentences from the same dialogue and partition provide context.

Modifications: lowercase, normalize apostrophes, tokenize, replace numbers,
retain soft commas, split at sentence endings, deduplicate normalized sentences,
hash-split 2% for evaluation, downsample SODA, restrict the word vocabulary using
the shipped dictionary, train a CIFG LSTM, and quantize weights to signed int8.
The attributions are also bundled in `NOTICE-dictionary.txt` on both platforms.

Raw downloads, per-file URLs/SHA-256/byte counts in `manifest.json`, processed text,
deduplication database, vocabulary and checkpoints stay in
`..\tk-lm-data\`. `provenance.json` records metadata only.
Tatoeba's export URL changes over time: reuse the checksummed local download to
reproduce this exact run. A fresh data directory intentionally discovers a new
snapshot; check the resulting manifest before comparing runs.
