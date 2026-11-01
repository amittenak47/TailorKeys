# Autocorrect rebuild: a transformer trained on taps and context

Agreed 2026-10-27. English only. Replaces items 1 and 3 of [next-fixes.md](next-fixes.md). The LSTM is removed
from every feature once the transformer passes the gates below.

## Rules

1. **What's written is fixed.** Earlier words and the letters actually typed are the context and the prior. They
   are not re-guessed.
2. **One decision when a word is finished.**
   - *Does it fit?* The model (meaning) and grammar (part of speech, helping verbs, agreement, capital I, a/an)
     judge the written word in its context. A word missing from the dictionary is not wrong by itself: it is a typo
     only when its letters and taps point at a word that fits much better. No length cutoffs and no special cases
     for short, taught or repeated words.
   - *If it doesn't fit*, the candidates are only words the taps could have meant, and the change is made only when
     one is clearly right. Otherwise the word stays and the options go in the wheel.
   - Capitals and apostrophes (I, I'm, the start of a sentence) are part of that same change, with one undo.
3. **Later passes change a written word only when it is more likely wrong than right.** Once the words after it
   exist, the same decision runs again with both sides. A word changes only when, given the context and its taps,
   it is more likely than not an error (checked in the eval that 50% really means half are wrong), and the
   replacement is the most likely word. The keyboard's own corrections are rechecked the same way and can switch
   back to what was typed.
4. **Show every change.** Changes are underlined so they can be seen and undone. A word with real doubt that falls
   short of more-likely-wrong is underlined as a suggestion and left alone. Android: SuggestionSpan with
   FLAG_AUTO_CORRECTION, tested per app on the tablet. iOS: a chip in the suggestion bar while the word is within
   the text the keyboard can read.

## Slang, names and words that must not change

- **Word lists:** AOSP plus wordfreq (to 2021). Possibly offensive AOSP words (cock, fag) become recognized-only
  instead of unknown. The curated list of real misspellings (Wikipedia's machine-readable list, CC BY-SA, 4,297
  entries) replaces the edit-distance filter when the model takes over (phase 6), not before:
  - The edit-distance filter drops 50,047 searchable wordfreq words; only 811 of them are on the list. The rest are
    mostly real: yup, whoa, geek, gif, docs, diy, ikea, bae, repost.
  - With the list instead (`build_extra_words.py --misspellings`), the current keyboard fails the gate: everyday
    typos fixed 831 -> 823, correctly typed words changed 19 -> 21, two wrong keys in a 4-letter word wrong 11% -> 14%.
    Today the keyboard mostly trusts a word it knows, so every added word is one more place a slip can land and
    stay; the model judges fit instead.
  - The run-together rule also drops g-dropping slang (goin, doin, sayin) and hehe; fix it with the switch.
  - Done for the model (2026-10-27): its own lists in `tk-lm-data/processed/words` use the curated list and let a
    dropped g and a doubled word (hehe) through. On the teacher's gate: everyday typos fixed 865 -> 868, wrong word
    8 -> 6, nothing else changed. The keyboard's assets keep the edit filter until phase 6.
- **Informal and offensive words** (2026-10-27, both lists): AOSP's informal flag (gonna, wanna, ain't, y'all,
  dunno) no longer drops a word. Words AOSP flags as offensive or possibly offensive (1,300, including sex, damn
  and hmm) are recognized, so never changed, but not offered unless the user turns off Block offensive words
  (`words_offensive.txt`, 817 words common enough to search). The keyboard's gate: unchanged, one more typo fixed.
- **Training text** includes recent web text, and simulated taps land on slang and brands too.
- **Don't-correct layer after the model**, two tiers:

  | Tier | What's in it | Rule |
  |---|---|---|
  | Hard | Corrections you undid, words you added, words capitalized mid-sentence (names) | Never changed automatically; at most an underline |
  | Soft | Slang and brand list | Changed only when the model is near-certain (about 90%) and the taps lean toward the other word |

- **Learning without extra steps:** one backspace right after a change restores the word and remembers it; a word
  typed twice is learned; the exact typed text sits first in the wheel after any change, and tapping it restores and
  learns it.
- **Domain word lists, protected (2026-10-28, phase 6):** lists for domains like code and game, film and TV lore
  (memoize, kwargs, useEffect, Malenia, Caelid). Their words join the candidate search, so a typo can be fixed
  toward them (useegfect -> useEffect, measured), and a word typed exactly from one is never changed automatically,
  like a word the user taught. Adding a word to the lists alone does not stop the model changing it when typed
  right (memoize -> memorize, measured), because the model judges from what it read; the protection does.
- **Add-word button (chosen 2026-10-28, phase 6):** a word kept twice that sits one key from a common word (memoize
  and memorize, teh and the) is not learned on its own, since it may be a habitual slip. Instead the gear between
  the wheels turns into a lit "+ word" pill for a few seconds or until the next word; tapping it adds the word and
  shows "Added" briefly. Ignored three times for the same word, it stops asking. Undo still teaches at once, and a
  word kept twice that is near no common word (Malenia) is still learned quietly.

## What the evaluation found (2026-10-27)

Desktop JVM, held-out sentences, the eval in `AutocorrectEvalTest` (see Gates for the commands).

1. **Most wrongly changed words were real words swapped for a more common neighbor at the space bar** (gold →
   good, lift → left, quite → quote): 32 of 41 on the full eval. The model saw only the left side, and a clean
   tap on the wrong key cost the new word 0.8 to 6.8 points against a model preference of 2 to 11.
2. **One tap weight cannot do both jobs.** The wheel's weight (8 times heavier) cut intended words changed from 41
   to 0 but stopped fixing skipped letters (68% to 0%). A skipped key has no tap, so it is not finger evidence.
3. **Separate slip weights work.** A wrong key weighed by where the tap landed, a skipped letter as a wildcard gap,
   an extra tap, a swap, a reach for space, each with its own weight. Sweep on 390 sentences:

   | Tap weight, flags | Intended words changed | Real-word slips fixed / wrong (of 277) | 2 wrong keys, 5+ letters | Skipped letter | Skipped + 1 wrong key | 1 wrong key, 4 letters |
   |---|---|---|---|---|---|---|
   | 0.4 (today) | 20 | 172 / 21 | 88% | 70% | 49% | 95% |
   | 0.8 | 14 | 168 / 13 | 86% | 75% | 50% | 95% |
   | 1.6 | 9 | 166 / 11 | 78% | 76% | 46% | 89% |
   | 0.8 + short-word reach | 16 | **260 / 1** | 86% | 78% | 63% | 95% |
   | 1.6 + short-word reach | 10 | **257 / 0** | 78% | 83% | 60% | 89% |

   - The skipped-letter wildcard must stay fairly expensive: making it cheaper tripled intended words changed.
   - **Short-word reach** (three-letter words may take a multi-slip fix the taps support, so ahs → and) fixes 94%
     of real-word slips instead of 61%, with no wrong fixes.
   - **Frequency cutoffs** (top 5,000 / 15,000) barely matter: off, or kept only at a sentence start, changes
     nothing except a few points on two-slip four-letter words. They go.
   - **Re-decoding's shortlist by taps instead of frequency** changes nothing measurable.
4. **Context with both sides is very strong; the left side alone is weak.** ModernBERT-base, sentence only, no taps:

   | | Both sides | Left only |
   |---|---|---|
   | Prefers the meant word over a real-word slip (517) | 98% | 87% |
   | A one-letter neighbor beats a correctly typed word (284) | 15% | 44% |
   | Prefers what was typed in the 41 wrongly changed words | 30 | 25 |

   It knows modern words with both sides (tiktok, github, bruh, matcha, lyft, yeet, meta; vlog narrowly loses).
   At the space bar the taps must carry the decision; words without clear typo evidence wait for the words after.

## Architecture

**One model, one input format:** left context (two sentences back), the slot word's taps (each tap's distance to
every letter key, after hand-drift adjustment, so any layout works), and the right context when it exists. Out:
a probability for each candidate word in the slot, including what was typed.

| Used as | Taps | Right context | Runs |
|---|---|---|---|
| Wheel at a word boundary | none | no | once per word, cached |
| Wheel while typing | the taps so far | no | per letter, a few tokens on the cached context |
| Space-bar decision | the finished word's taps | no | once per word |
| Recheck an earlier word | its taps | the words after it | after each later word, then at the sentence end |

**What each feature uses:**

| Feature | Now | After |
|---|---|---|
| Right wheel | LSTM, candidate search, taps | Transformer, candidate search, taps |
| Left wheel (varied, temperature 2.5) | Same plus the varied draw | Same plus the varied draw |
| Wheel with nothing typed | LSTM top 300 | Transformer top words |
| Key hints | LSTM, candidate search | Transformer, candidate search |
| Autocorrect at the space bar | LSTM plus hand-set weights | Transformer probability, rule 2 |
| Look-back and re-decoding | LSTM plus hand-set weights | One recheck, rule 3 |
| Swipe typing | Shape match plus LSTM | Shape match plus transformer |
| Tidy | Autocorrect | The new decision |
| Chords | Top 20k words | Unchanged |

**What happens to each piece:**

| Piece | Change |
|---|---|
| LSTM (lm_en.bin) | Removed after the gates pass |
| Pair tables, frequency cutoffs, hand-set weights, margins, short-word rules | Removed |
| Look-back and re-decoding formulas | Replaced by the recheck |
| Word lists | Kept: which words exist and where the search looks |
| Candidate search | Kept, simplified: only job is not to miss the right word; short words reach two letters away |
| Tap features, hand drift | Kept; they become the model's input |
| Your learning, undo memory, name rule, password safety, don't-correct tiers | Kept, after the model |
| Transformer student, 10 to 20M parameters, int8 | Added |
| Inference runtime (LiteRT or ONNX Runtime) | Added; the keyboard's first runtime dependency, decided with the user |
| Underline | Added (Android) |

## Training

On the RTX 4090 (24 GB), in `tk-lm-data` (venv-mbert), never in git.

1. **Text** (`tools/lm/corpus.py`): Common Voice, Tatoeba and SODA kept as written (capitals, apostrophes,
   punctuation), split by the same sentence hash as the LSTM's data so eval sentences never reach training; plus one
   shard of FineWeb's newest snapshot (CC-MAIN-2025-26, mid-2025 web text, ODC-By), only sentences that read like
   prose. Training draws SODA 40%, FineWeb 30%, Tatoeba 25%, Common Voice 5%.
2. **Tap simulator** (`tools/lm/taps.py`, `dataset.py`): a typist per sentence (scatter from careful to sloppy,
   key shape, leftover hand drift, its own rates of skipped, extra, swapped and doubled keys, reaches for space,
   left-out apostrophes) on the split, standard, thumbs, curved and a staggered phone layout, every key read as the
   keyboard reads it. One sentence in ten has a real misspelling from the curated list, typed carefully.
   Capitals are never tapped, so labels teach I, names and sentence starts. Clean words are labeled as themselves.
   Matched to the JVM eval: with the eval's everyday rules on the eval's 750 sentences it types 14.7% of words
   differently (20 seeds, 14.0% to 15.3%); the JVM eval typed 14.2%. Training types the text afresh every epoch;
   a fixed validation set comes from the eval split.
3. **Teacher** (`tools/lm/teacher.py`): ModernBERT-base plus a tap encoder. One example is one word slot; its
   candidates are what was typed (keeping it), the words the taps could have meant (`candidates.py`: wrong keys,
   a skipped key, an extra tap, a swap, a left-out apostrophe, and two words where a tap near the space bar was
   the space) and, in training, usually the meant word. Each candidate is one pass over: the left context, the
   candidate in the slot, the right context when it exists; the candidate spelled out, each letter carrying the
   tap the search matched to it (or a gap marker for a skipped key); the slot's taps as learned tap tokens. So
   meaning, spelling and taps are judged together, with no hand-set weights. A softmax over the candidates picks
   one; a second head decides the capital (I, names). In one slot in five the meant word is not added when the
   search missed it, and a word outside the lists never is: the right answer is then to leave the word as typed.
   Scored two ways: held-out validation slots, and the JVM gate's own cases (`TK_AC_EXPORT` writes them;
   `teacher.py gate` types each sentence word by word, keeping up to N readings of the sentence alive where a
   word is uncertain, then rechecks every word with both sides under rule 3, with the letters actually typed
   always offered so a change can be undone; `--dump` writes every wrong decision). Its result is the ceiling.
4. **Students** (`tools/lm/student.py`, shape chosen 2026-10-27): 2 or 3 sizes from 10M to 20M parameters, their
   own smaller vocabulary, trained on the true answers and then on the teacher's probabilities over candidates, with
   int8 compression in training. The teacher reads the whole sentence once per candidate, too slow for the tablet
   and for the wheel on every letter, so a student is a left-to-right model that reads once and keeps what it read:
   the context (with the words after the slot first, marked as after the gap, when rechecking), then the slot's taps
   one token each. Each candidate adds a few tokens on top of that saved reading (its word pieces, its letters each
   pointing at the tap the search matched, a token per extra tap, a score token), so candidates never pay for the
   context again. After each tap a word output over the 20k most common words gives the wheel for the letters so
   far; before the first tap it gives the wheel with nothing typed. The context also trains as plain next-piece
   prediction, so the student learns English from all of the text it reads. If the space-bar decision is too slow
   on the tablet, candidates can be made to use only the last layers (a thinner scorer) without starting over.
   Distillation: the teacher scores 1.5M fresh slots once (`teacher.py label`, about 100 a second), saved with
   their candidates; the student, starting from the first student, learns 70% from matching the teacher's spread
   (both softened at temperature 2) and 30% from the true answer. A "known word" input on each candidate was tried
   and dropped (2026-10-28): it is on for nearly every real word, form and from included, so it tells the model
   little, and domain words are better served by protected lists (phase 6) and by text from those domains.
5. **Calibration:** check that a predicted 50% is wrong half the time; rule 3 depends on it. The simulator's
   typists range from clean to sloppy but average about the eval's everyday rate (16% of words typed differently
   against the eval's 14%); the first teacher, trained at 28%, doubted clean taps on rare words and names. The
   rate is set again from real taps (phase 7).

### First teacher (2026-10-27)

Trained 1.3 hours (flat after the first 15 minutes) at the old 28% typo rate, with the meant word always among the
candidates and no alignment. On the gate, after the recheck, against the baseline below:

| Measure | Keyboard | Teacher |
|---|---|---|
| Careful words changed (of 6,156) | 22 | 17 |
| Real-word slips fixed / wrong word (of 517) | 458 / 9 | 502 / 6 |
| Everyday typos fixed / wrong word (of 876) | 831 / 6 | 856 / 17 |
| Words typed right, changed (of 5,281) | 19 | 18 |
| Mid-sentence I capitalized (of 48) | 0 | 48 |

What went wrong, from the dump: clean taps on rare words and names were overruled (gold -> good, djibouti ->
Shivpuri, muiriel -> Muriel); a space-bar change stayed, because the recheck could not offer the letters typed;
one wrong word pulled the next along (just -> must, then seems -> seem); a word was changed although nothing
offered was meant (it never learned to leave a word alone); and a skipped key plus a wrong key often fell off the
candidate lists (91.7% found). Two of the counted wrong words are errors in the source text (its, fathers). The
next run changes all five: typo rate, aligned taps, leaving words as typed, undo and branching at the gate, and a
fourth candidate list for words with a skipped key (97.7% found).

### Second teacher (2026-10-27)

Trained 2 hours from ModernBERT-base (not from the first teacher) with all five changes; best at step 26,000, still
creeping up. On the gate, after the recheck:

| Measure | Keyboard | First teacher | Second teacher |
|---|---|---|---|
| Careful words changed (of 6,156) | 22 | 17 | 7 |
| Real-word slips fixed / wrong word (of 517) | 458 / 9 | 502 / 6 | 511 / 2 |
| Everyday typos fixed / wrong word (of 876) | 831 / 6 | 856 / 17 | 865 / 8 |
| Words typed right, changed (of 5,281) | 19 | 18 | 8 |
| Mid-sentence I capitalized (of 48) | 0 | 48 | 48 |

Branching made no difference: every cap from 1 to 32 gave the same result, because almost every word is at 90% or
more (1.04 branches held on average, a cap of 4 never reached, no earlier word switched). The misses left are the
model being sure and wrong, which the recheck already sees with both sides. Of the 32 wrong decisions, 5 come from
the two source-text errors (its, fathers); gonna is not in the word lists; the rest are rare words read as common
ones (cub -> club, bunt -> blunt, bold -> old, slop -> slip), a name (hiromi), British spelling (metre -> meter),
ambiguous taps (tpped: tipped or tapped; lovred: loved or lived) and a split taken over a skipped key (alpong ->
All of). Skipped + 1 wrong key is still the weakest kind (82.2% fixed, 13.0% wrong word, against 77.6 / 17.3).

### First student (2026-10-28)

Size m (15M parameters, float), trained 4 hours on the true answers only (83,600 steps of 64 slots, about 5.4M
slots), no teacher probabilities yet. Validation score 0.919 against the teacher's 0.953; the wheel holds the meant
word in its top 3 after 1 tap 61% of the time, after 2 taps 71%. On the gate, after the recheck:

| Measure | Keyboard | Teacher | Student m |
|---|---|---|---|
| Careful words changed (of 6,156) | 22 | 7 | 16 |
| Real-word slips fixed / wrong word (of 517) | 458 / 9 | 511 / 2 | 486 / 10 |
| Everyday typos fixed / wrong word (of 876) | 831 / 6 | 868 / 6 | 838 / 13 |
| Words typed right, changed (of 5,281) | 19 | 8 | 18 |
| Mid-sentence I capitalized (of 48) | 0 | 48 | 48 |
| Typo kinds at the space bar, % fixed / wrong | see baseline | | 1 wrong key 96 / 1; 2 wrong keys, 4 letters 92 / 7; 2 wrong keys, 5+ letters 93 / 4; skipped letter 85 / 3; skipped + 1 wrong key 56 / 18; extra tap 94 / 1; reach for space 99 / 0.5; space on n/b 98 / 0.5 |

It beats the keyboard on careful words, slips fixed, two wrong keys and capital I, but not the gate: wrong words
rise (everyday typos 6 -> 13, slips 9 -> 10, skipped + 1 wrong key 11% -> 18%). Unlike the teacher's, its recheck
makes things slightly worse (slips wrong word 8 -> 10, words typed right changed 15 -> 18): reading the words after
the gap before the slot is harder for a left-to-right model than reading both sides.

### Second round plan (2026-10-28)

The first student showed the limits of the first corpus: words it never read (memoize, kwargs, Malenia) and rare
names it changed (Ciri -> Chiri). The second round starts both models over, entirely on the local RTX 4090:

1. **Domain text** (`domains.py`): 42 Stack Exchange sites (April 2024 dump), four more FineWeb files (the pages
   that talk like the internet kept as their own source), and Wikipedia articles on games, film and TV, anime,
   music, sports, medicine, software and internet culture; Wiktionary for slang, names and field terms. Training
   now draws everyday writing 40%, general web 25%, the domains 35% (`teacher.WEIGHTS`), each source read from
   chunks all over it (`corpus.py --chunk`), not from its start. Domain word lists join the candidate search
   (`words_domains.txt`). A standing domain test (`domain_test.py`, 170 sentences in 17 areas, typed carefully
   and with slips) runs after every model.
2. **Teacher:** ModernBERT-large (395M), fine-tuned on the new mix.
3. **Teacher scores** 1.5M fresh slots for distillation.
4. **Student, 50M** (51.7M parameters): plain reading of the text first (`student.py read`, sentences packed back
   to back, about 166,000 pieces a second), then typing, then distillation.
5. The gate and the domain test against the first student, the teacher and today's keyboard.

### Tablet timing (2026-10-28)

Untrained students timed on the tablet (XPPen Magic NotePad, MediaTek MT8781, Android 14) by `tools/runtime-bench`
(models from `tools/lm/bench_export.py`), 8-bit weights, 4 CPU threads, on a saved reading of 100 tokens:

| Size | Wheel, per letter | Reading a word | Space bar, 25 candidates | Space bar, 12 candidates | 8-bit file |
|---|---|---|---|---|---|
| 15M | 3.7 ms | 4.4 ms | 105 ms | | 12 MB |
| 20M | 4.1 ms | 5.7 ms | 125 ms | | 17 MB |
| 35M | 7.3 ms | 10.8 ms | 226 ms | | 32 MB |
| 50M | 9.4 ms | 14.5 ms | 280 ms | 146 ms | 47 MB |

Fastest of LiteRT and ONNX Runtime per cell (LiteRT on the small steps, ONNX Runtime on the space bar). The
graphics chip and the AI accelerator were slower than the CPU on this chip (50M space bar, float: 920 ms on the
graphics chip, 967 ms through NNAPI; ONNX Runtime's NNAPI path failed). The student is 50M (chosen 2026-10-28):
accuracy comes before speed, and a correction may land after the user has typed on, as look-back already does.

## Gates

`AutocorrectEvalTest.gate` (`TK_AC_GATE=1`) measures the shared sentence cases (250 sentences per source: careful,
one real-word slip, everyday typing, one typo of each kind) and fails when any count of wrong changes rises, or any
fix count or rate falls by more than a point, against `app/src/test/resources/autocorrect_gate.txt`.
`TK_AC_GATE_RECORD=1` records a new baseline after an intended improvement. It also prints the distance to the
targets: careful words changed in single digits, at least 94% of real-word slips fixed, I capitalized mid-sentence
every time. Then on the tablet: per-letter wheel time well under the gap between taps, per-word decision under
200 ms, memory and APK size. `TK_AC_DUMP=1` with `TK_AC_SENTENCES=1` lists every case with the numbers behind it.

Baseline recorded 2026-10-27, after the interim settings below:

| Measure | Value |
|---|---|
| Careful words changed (of 6,156) | 22 |
| Real-word slips fixed / wrong word (of 517) | 458 / 9 |
| Everyday typing: typos fixed / wrong word (of 876) | 831 / 6 |
| Everyday typing: words typed right, changed (of 5,281) | 19 |
| Mid-sentence I capitalized (of 48) | 0 |
| Typo kinds, % fixed / wrong | 1 wrong key, 4 letters 92 / 2; 2 wrong keys, 4 letters 67 / 11; 2 wrong keys, 5+ letters 68 / 3; skipped letter 83 / 3; skipped + 1 wrong key 56 / 11; extra tap 94 / 1; reach for space 99 / 0; space landed on n/b 86 / 0 |

## Interim settings (applied 2026-10-27)

Separate slip weights with a tap weight of 1.6 (a letter without a tap keeps the old .4, judged by layout
neighbours), short-word reach when there are taps and the model is loaded, a rejected best fix keeps the word as
typed, and Confident leaves a word alone when a split it won't make explains it best. The frequency cutoffs stay:
off, they let three slips reach rare extended-list words (teadt -> traff) for almost no gain. Against the previous
settings on the full eval: careful words changed 41 -> 22, real-word slips fixed 66% -> 89% (wrong 43 -> 8), skipped
letters fixed 68% -> 83%; two wrong keys in a long word dropped 83% -> 68%, the cost of trusting taps more.

## Phases

1. **Eval gate test** and the everyday-typing pass (done 2026-10-27).
2. **Dataset:** text, tap simulator, curated misspelling list (built 2026-10-27, FineWeb added).
3. **Teacher** fine-tune and eval.
4. **Students**, distillation, int8, eval against the teacher.
5. **Tablet:** runtime choice, speed, memory, size.
6. **Android integration** behind a setting, replacing the LSTM feature by feature; underline; don't-correct tiers.
7. **Real taps** (opt-in, kept on the tablet) to check and tune; later iOS (Core ML).

## Into the keyboard (from 2026-11-01)

**The model:** the 50M student after typing (`student-50m/best.pt`, step 92,000, held-out score 0.9435). Distillation
was tried and lost on every check (best 0.936; on the gate 14 careful words changed against 6), so it is not used.

**Runtime: ONNX Runtime** (chosen 2026-11-01): the space bar is faster than LiteRT (280 against 430 ms on the
tablet), it exports straight from PyTorch, and the PC checks run the same file. LiteRT was faster per letter (9.4
against 12.7 ms) and used less memory.

**Export** (`tools/lm/export.py`): one graph, `step`, about 50 MB at int8, that reads rows of new tokens on top of a
saved reading (the keys and values of everything read so far). One row while reading (the context up to `<fill>`,
then each tap as it lands, with the wheel after each); one row per candidate when scoring. Separate reading and
scoring graphs would have shipped the same weights twice. In float it matches PyTorch exactly; at int8 the held-out
score stays 0.9435 and the wheel's top 3 after one tap goes from 63.0% to 61.7%. Per-channel weights and float output
layers changed nothing. On the gate, after the recheck (`export.py gate`, reading taps one at a time as the keyboard
will):

| Measure | PyTorch | int8 graph |
|---|---|---|
| Careful words changed (of 6,156) | 6 | 6 |
| Real-word slips fixed / wrong word (of 517) | 501 / 5 | 501 / 4 |
| Everyday typos fixed / wrong word (of 876) | 856 / 9 | 856 / 10 |
| Words typed right, changed (of 5,281) | 6 | 6 |
| Mid-sentence I capitalized (of 48) | 48 | 48 |

**Ports, each checked against Python** (`tools/lm/app_fixtures.py` writes the cases):

| Piece | Kotlin | Check |
|---|---|---|
| Candidate search and shape list (`candidates.py`) | `TapSearch` | `TapSearchTest`: 540 words on every training layout, everyday, sloppy and looking away; the same candidates, shape words and alignments. Words tied exactly on tap cost may differ, as numpy picks among ties in no set order |
| Word pieces (byte-level BPE) | `StudentTokenizer` | `StudentTokenizerTest`: 417 texts, identical |
| A slot's input (`StudentSlots.encode`, the candidate cap) | `StudentSlot` | `StudentSlotTest`: 150 gate words through the int8 graph; the same choice and top wheel word every time, scores within 0.03 |
| The graph | `StudentModel` (ONNX Runtime 1.30 on Android) | the PC tests use the desktop runtime 1.17.3: later Windows builds need a newer C++ runtime than the JDK ships |

**The trained model on the tablet** (2026-11-01, `tools/runtime-bench`, int8, 4 CPU threads; the shapes the keyboard
uses):

| Step | Median | Slowest tenth |
|---|---|---|
| A tap read onto a 70-token reading (the wheel after each letter) | 14.1 ms | 19.1 ms |
| A word's 6 pieces read | 17.2 ms | 18.0 ms |
| A whole context of 66 pieces read at once | 70.1 ms | 81.4 ms |
| Space bar, 25 candidates | 297 ms | 302 ms |
| Space bar, 12 candidates | 149 ms | 153 ms |

Loading the model and the first run take about 1 second, once per keyboard start.

**In the keyboard** (2026-11-01, behind Settings > Autocorrect > Model autocorrect (test), off by default):

- Each tap also gives the model's features (`KeyboardView.onModelTap`): squared distances to a to z, the apostrophe
  key and the space bar, across in key spacings and down in row spacings, as the simulator's layouts are spaced (the
  old autocorrect's distances use the key width both ways and leave the apostrophe out). An apostrophe tapped inside
  a word keeps the word's taps, so don't reaches the model with all five.
- `StudentCorrector` makes the gate's two decisions: at the space bar the best candidate is written (rule 2); the
  word before is rechecked with both sides when the next word is finished, and changes only when the model finds it
  more likely wrong than right (rule 3). It keeps the reading of the text before and reads only new pieces.
- `StudentEngine` loads the model (copied out of the APK once) on its own thread. The space appears at once; the fix
  lands about 0.3 s later, replacing the word in place with whatever was typed since kept after it; backspace right
  away undoes it. Words capitalized mid-sentence (names), words with digits, corrections undone twice and taught words
  are left alone. Whole-sentence re-decoding and look-back are off while it is on.
- Known gap: a word whose decision is still running when the next space comes is not rechecked, and the next word's
  fix can be dropped (typing faster than the decisions).
- On the tablet (2026-11-01, typed by scripted taps on the split layout): "i think thsi is a good ides" and "i will
  send it to yuo tomorow becuase it is lste" came out right. Each fix first landed 1.2 to 1.6 s after its space:

  | Per decision | Before | After |
  |---|---|---|
  | Candidate search (408,000 words) | 260 to 620 ms | 18 to 130 ms (no boxed scoring calls; a swap or skip costs its change, not a fresh sum) |
  | The recheck's search | the same again | 0 (the same taps' result is kept) |
  | Reading the context | about 45 ms | about 45 ms |
  | Scoring 17 to 37 candidates | 170 to 410 ms | 170 to 410 ms |
  | Fix on screen after the space | 1.2 to 1.6 s | 0.4 to 0.6 s (the word's fix lands before the recheck of the word before runs) |

- Digits typed inside a word (the number row sits right above the letters): h3llo became hello and w3nt became went,
  while mp3 stayed. A digit or symbol is only changed into a letter whose key its tap landed beside (within about a
  row), and only in words that are mostly letters. The model takes a few seconds to load when the keyboard starts; words typed before then are left.
  Two bugs found and fixed there: Android's regex has no UNICODE_CHARACTER_CLASS (the tokenizer now lists Unicode
  white space itself), and a late fix moved the text under the word being typed, which dropped that word's taps.

**The keyboard's path on the gate** (`StudentGateTest`, the same sentences, context and taps as Python's gate, run
through `StudentCorrector` on the PC): within one word of Python's int8 numbers on every count.

| | Python, space bar | Kotlin, space bar | Python, recheck | Kotlin, recheck |
|---|---|---|---|---|
| Careful words changed (of 6,156) | 9 | 8 | 6 | 7 |
| Real-word slips fixed / wrong word (of 517) | 491 / 7 | 491 / 8 | 501 / 4 | 501 / 5 |
| Everyday typos fixed / wrong word (of 876) | 853 / 11 | 854 / 10 | 856 / 10 | 856 / 10 |
| Words typed right, changed (of 5,281) | 12 | 11 | 6 | 7 |

The model's word lists (`words_extra.txt` with the curated misspellings, the domain lists, the offensive lists), its
tokenizer and its wheel words are in `assets/model/`. The model file is copied in from `tk-lm-data/export` and kept
out of git until we decide how it ships.
