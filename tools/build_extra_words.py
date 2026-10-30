#!/usr/bin/env python3
"""Build the English words beyond words.txt: the rest of the AOSP list plus modern words from wordfreq.

Usage: python tools/build_extra_words.py [--source wordlist.combined.gz] [--search-zipf 1.25]
Needs wordfreq==3.1.1 (pip install wordfreq==3.1.1). words.txt must already exist (build_dictionary.py).

Writes three assets, ranked by wordfreq frequency:
  words_extra.txt      searched for corrections when a word is finished (zipf >= --search-zipf)
  words_known.txt      only recognized as real words, never offered as corrections
  words_offensive.txt  the offensive words common enough to search, searched too when the user allows them
A word from wordfreq that AOSP lacks is left out when it looks like a misspelling: one edit (or an apostrophe)
away from a word at least 50 times as common (teh, thier, recieve), a contraction without its apostrophe
(dont, im), or two words run together, one of them very common (ofthe, alot). Words AOSP flags as offensive are
recognized but never offered as corrections; words it flags only as informal (gonna, ain't, y'all) are kept.

words_extra.txt lines are "word<TAB>rank": where the word's wordfreq frequency would place it among words.txt,
so the keyboard can judge how common it is (a word near the 15,000th of words.txt in frequency, not after its
60,000th just because it is listed later).
"""

import argparse
import gzip
import re
import statistics
import unicodedata
from pathlib import Path

from build_dictionary import (ASSETS, DEFAULT_COMMIT, download, excluded, fields,
                              source_url, valid_word, write_preserving_newlines)

WORDFREQ_VERSION = "3.1.1"
# 50 times as common: teh/the, thier/their and recieve/receive clear it; meta/meat (about 6x) does not.
TYPO_RATIO_ZIPF = 1.7
# Run-together phrases common enough in real text to pass every other check.
RUN_TOGETHER = {
    "alot", "noone", "aswell", "infact", "atleast", "eachother", "incase", "everytime", "thankyou", "alittle",
    "abit", "ofcourse", "inorder", "aslong", "highschool",
}
LETTERS = "abcdefghijklmnopqrstuvwxyz'"
# Two words run together (ofthe, atleast) are a misspelling when one of them is among this many most common words,
# both have two letters or more (a + sync is async), and the phrase is this much more common (wordfreq zipf) than the
# joined word; smartwatch and livestream stay.
RUN_TOGETHER_TOP = 200
RUN_TOGETHER_ZIPF = 2.0


def informal(attributes):
    """AOSP flags informal words (gonna, ain't, y'all) as nonwords. They are typed every day, so they count as words
    unless another flag excludes them."""
    flags = set(re.split(r"[:;,|\s]+", attributes.get("flags", "").lower().replace("-", "_")))
    return "nonword" in flags and not excluded({**attributes, "flags": ",".join(flags - {"nonword"})})


def aosp_words(text):
    """Every eligible AOSP word with its frequency, every word AOSP flags as offensive or possibly offensive, and
    every word it flags as not a word (yall, wierd)."""
    frequencies, offensive, not_words = {}, set(), set()
    for line in text.splitlines():
        line = line.strip()
        if not line.startswith("word="):
            continue
        attributes = fields(line)
        word = unicodedata.normalize("NFC", attributes["word"].lower())
        if excluded(attributes) and not informal(attributes):
            named = attributes.get("flags", "").lower() + " " + " ".join(k for k, v in attributes.items() if v.lower() == "true")
            (offensive if "offensive" in named else not_words).add(word)
        elif int(attributes["f"]) and valid_word(word):
            frequencies[word] = max(frequencies.get(word, 0), int(attributes["f"]))
    return frequencies, offensive, not_words


def edits(word):
    for i in range(len(word) + 1):
        a, b = word[:i], word[i:]
        if b:
            yield a + b[1:]
        if len(b) > 1:
            yield a + b[1] + b[0] + b[2:]
        for c in LETTERS:
            if b:
                yield a + c + b[1:]
            yield a + c + b


def misspelling(word, zipf, base, top, phrase, listed=None):
    """Why [word] looks like a misspelling of a more common word, or None. [phrase] gives a phrase's zipf. With
    [listed] (known misspellings), a word is a typo only when listed, instead of when one edit from a commoner word."""
    if word in RUN_TOGETHER:
        return "run-together"
    own = zipf.get(word, 0.0)
    # With the curated list, a dropped g (goin, sayin) is not two words run together, nor is a word said twice (hehe).
    dropped_g = listed is not None and word.endswith("in") and word + "g" in base
    for i in range(1, len(word)):
        first, second = word[:i], word[i:]
        if dropped_g or listed is not None and first == second:
            continue
        if len(first) > 1 and len(second) > 1 and (first in top or second in top) and first in base and second in base and \
                phrase(first + " " + second) >= own + RUN_TOGETHER_ZIPF:
            return "run-together"
    if "'" not in word:
        for i in range(1, len(word)):
            if word[:i] + "'" + word[i:] in base:
                return "missing apostrophe"
    if listed is not None:
        return "listed misspelling of " + listed[word][0] if word in listed else None
    for other in edits(word):
        if other != word and zipf.get(other, 0.0) >= own + TYPO_RATIO_ZIPF:
            return "typo of " + other
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--commit", default=DEFAULT_COMMIT, help="Exact AOSP commit (40 hex digits)")
    parser.add_argument("--source", type=Path, help="Local en_US combined text or gzip from this commit")
    parser.add_argument("--search-zipf", type=float, default=1.25,
                        help="Words at least this common (wordfreq zipf) are searched for corrections")
    parser.add_argument("--output-dir", type=Path, default=ASSETS)
    parser.add_argument("--misspellings", type=Path,
                        help="misspellings.tsv from tools/lm/misspellings.py: reject listed words instead of every "
                             "word one edit from a much commoner one")
    args = parser.parse_args()

    import wordfreq
    from importlib.metadata import version
    if version("wordfreq") != WORDFREQ_VERSION:
        raise SystemExit(f"Expected wordfreq {WORDFREQ_VERSION}, found {version('wordfreq')}")

    url = source_url(args.commit, "en_US")
    data = args.source.read_bytes() if args.source else download(url)
    if data.startswith(b"\x1f\x8b"):
        data = gzip.decompress(data)
    aosp, offensive, not_words = aosp_words(data.decode("utf-8"))

    base_path = args.output_dir / "words.txt"
    base = [w.strip() for w in base_path.read_text(encoding="utf-8").splitlines() if w.strip()]
    base_set = set(base)
    top = set(base[:RUN_TOGETHER_TOP])

    zipf = {}
    for word in wordfreq.top_n_list("en", 10 ** 7, wordlist="large"):
        word = unicodedata.normalize("NFC", word)
        if valid_word(word) and word.isascii() and not word.startswith("'") and not word.endswith("'"):
            zipf[word] = wordfreq.zipf_frequency(word, "en", wordlist="large")
    # wordfreq counts some words in pieces (y'all as y + all), so it has no count for them. They get the median
    # wordfreq frequency of the AOSP words with the same AOSP frequency.
    by_aosp = {}
    for word, f in aosp.items():
        if word in zipf:
            by_aosp.setdefault(f, []).append(zipf[word])
    for word, f in aosp.items():
        if word not in zipf and f in by_aosp and len(wordfreq.tokenize(word, "en")) > 1:
            zipf[word] = statistics.median(by_aosp[f])

    listed = None
    if args.misspellings:
        listed = {}
        for line in args.misspellings.read_text(encoding="utf-8").splitlines():
            wrong, *fixes = line.split("	")
            listed[wrong] = fixes

    rejected = {}
    extra = []
    for word in set(aosp) | set(zipf) | offensive:
        if word in base_set or word in not_words:
            continue
        if word in offensive:
            if valid_word(word):
                extra.append(word)
            continue
        if word not in aosp:
            if len(word) < 2:
                continue
            reason = misspelling(word, zipf, base_set, top, lambda text: wordfreq.zipf_frequency(text, "en", wordlist="large"),
                                 listed)
            if reason:
                rejected[word] = reason
                continue
        elif "'" not in word and any(word[:i] + "'" + word[i:] in base_set for i in range(1, len(word))):
            # A rare AOSP word that is also a contraction without its apostrophe (wont) stays correctable.
            rejected[word] = "missing apostrophe"
            continue
        extra.append(word)
    # By modern frequency; AOSP words wordfreq lacks follow, in AOSP order.
    extra.sort(key=lambda w: (-zipf.get(w, 0.0), -aosp.get(w, 0), w))
    # Offensive words are recognized, so never changed, but never offered as a correction.
    common = [w for w in extra if zipf.get(w, 0.0) >= args.search_zipf]
    searched = [w for w in common if w not in offensive]
    known = [w for w in extra if zipf.get(w, 0.0) < args.search_zipf or w in offensive]
    # Offensive words common enough to search, for when the user allows them.
    allowed = [w for w in common if w in offensive]
    base_zipf = sorted((zipf.get(w, 0.0) for w in base), reverse=True)

    def ranked(words):
        """Each word with where its frequency places it among words.txt: how many of those words are more common."""
        lines, at = [], 0
        for word in words:  # most common first, so the count only grows
            while at < len(base_zipf) and base_zipf[at] > zipf[word]:
                at += 1
            lines.append(f"{word}\t{at}\n")
        return "".join(lines)

    write_preserving_newlines(args.output_dir / "words_extra.txt", ranked(searched))
    write_preserving_newlines(args.output_dir / "words_known.txt", "\n".join(known) + "\n")
    write_preserving_newlines(args.output_dir / "words_offensive.txt", ranked(allowed))
    notice = (
        "TailorKeys extended English word lists attribution\n\n"
        "Covers words_extra.txt, words_known.txt and words_offensive.txt.\n\n"
        "1. wordfreq data\n"
        "Project: wordfreq, by Robyn Speer and contributors\n"
        f"Version: {WORDFREQ_VERSION} (English 'large' list)\n"
        "Source URL: https://github.com/rspeer/wordfreq\n"
        "License: Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0)\n"
        "License URL: https://creativecommons.org/licenses/by-sa/4.0/\n"
        "wordfreq's data is built from Wikipedia, OpenSubtitles, SUBTLEX, NewsCrawl, GlobalVoices,\n"
        "Google Books Ngrams, OSCAR, Twitter and Reddit; see its README for the full source credits.\n\n"
        "2. AOSP LatinIME en_US word list (words beyond words.txt)\n"
        f"Source URL: {url}\n"
        f"Source commit: {args.commit}\n"
        "License: Apache License, Version 2.0 (see NOTICE-dictionary.txt)\n\n"
        "Modified by TailorKeys: lowercased, NFC normalized, filtered to letters and apostrophes,\n"
        "words already in words.txt removed, words AOSP flags as offensive moved to words_known.txt\n"
        "and listed in words_offensive.txt, likely misspellings removed, ranked by wordfreq frequency\n"
        "and split by frequency into the files.\n"
        "These files are licensed under CC BY-SA 4.0.\n"
    )
    write_preserving_newlines(args.output_dir / "NOTICE-words-extra.txt", notice)

    print(f"words.txt: {len(base)}; searched extra: {len(searched)}; known only: {len(known)}; "
          f"left out as misspellings: {len(rejected)}")
    by_reason = {}
    for word, reason in rejected.items():
        by_reason.setdefault(reason.split(" of ")[0], []).append(word)
    for reason, words in sorted(by_reason.items()):
        top = sorted(words, key=lambda w: -zipf.get(w, 0.0))[:12]
        print(f"  {reason}: {len(words)}, e.g. {', '.join(top)}")


if __name__ == "__main__":
    main()
