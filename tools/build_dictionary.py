#!/usr/bin/env python3
"""Build shared keyboard assets from an AOSP LatinIME combined word list.

Usage: python tools/build_dictionary.py [en_US] [--source wordlist.combined.gz]
Use --notice-source NOTICE with --source for a fully offline build. Downloads
are streamed to temporary files; only generated assets and attribution are written.
"""

import argparse
import base64
import gzip
import re
import shutil
import tempfile
import unicodedata
from pathlib import Path
from urllib.error import HTTPError
from urllib.request import urlopen


REPOSITORY = "https://android.googlesource.com/platform/packages/inputmethods/LatinIME"
DEFAULT_COMMIT = "127336e9f29d69607eab55982324b210279ae8c5"
ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
EXCLUDED_FLAGS = {"offensive", "possibly_offensive", "not_a_word", "nonword", "non_word"}


def download(url):
    with tempfile.TemporaryFile() as encoded, tempfile.TemporaryFile() as decoded:
        with urlopen(url, timeout=60) as response:
            shutil.copyfileobj(response, encoded, 1024 * 1024)
        encoded.seek(0)
        # Gitiles returns one unbroken base64 line; decode bounded chunks.
        while chunk := encoded.read(1024 * 1024):
            decoded.write(base64.b64decode(chunk, validate=True))
        decoded.seek(0)
        return decoded.read()


def source_url(commit, language):
    return f"{REPOSITORY}/+/{commit}/dictionaries/{language}_wordlist.combined.gz?format=TEXT"


def load_source(args):
    url = source_url(args.commit, args.language)
    if args.source:
        return args.source.read_bytes(), url
    languages = [args.language]
    if args.language == "en_US":
        languages.extend(["en", "en_GB"])
    for language in languages:
        url = source_url(args.commit, language)
        try:
            return download(url), url
        except HTTPError as error:
            if error.code != 404 or language == languages[-1]:
                raise
    raise ValueError("No dictionary found")


def fields(line):
    # Combined format uses key=value attributes, not quoted CSV. Splitting at
    # attribute boundaries also handles punctuation inside (discarded) words.
    return dict(part.split("=", 1) for part in
                re.split(r",(?=[A-Za-z_][A-Za-z_0-9]*=)", line.strip()))


def excluded(attributes):
    flags = set(re.split(r"[:;,|\s]+", attributes.get("flags", "").lower().replace("-", "_")))
    if flags & EXCLUDED_FLAGS:
        return True
    return any(attributes.get(flag, "").lower() in {"true", "1", "yes"}
               for flag in EXCLUDED_FLAGS)


def valid_word(word):
    return (1 <= len(word) <= 24 and any(c.isalpha() for c in word)
            and all(c.isalpha() or c == "'" for c in word))


def convert(text):
    frequencies = {}
    bigrams = {}
    previous = None
    for line in text.splitlines():
        line = line.strip()
        if line.startswith("word="):
            attributes = fields(line)
            word = unicodedata.normalize("NFC", attributes["word"].lower())
            frequency = int(attributes["f"])
            if not 0 <= frequency <= 255:
                raise ValueError(f"Invalid unigram frequency: {line}")
            previous = None
            if frequency and valid_word(word) and not excluded(attributes):
                frequencies[word] = max(frequencies.get(word, 0), frequency)
                previous = word
        elif line.startswith("bigram=") and previous is not None:
            attributes = fields(line)
            frequency = int(attributes["f"])
            if not 0 <= frequency <= 255:
                raise ValueError(f"Invalid bigram frequency: {line}")
            if not excluded(attributes):
                pair = (previous, attributes["bigram"].lower())
                weight = max(1, round(frequency / 255 * 100))
                bigrams[pair] = max(bigrams.get(pair, 0), weight)
    words = sorted(frequencies, key=lambda word: (-frequencies[word], word))[:60000]
    return words, bigrams


def merge_bigrams(path, words, source_pairs):
    vocabulary = set(words)
    pairs = {}
    if path.exists():
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            previous, word, weight = line.split()
            weight = int(weight)
            if not 1 <= weight <= 100:
                raise ValueError(f"Invalid existing bigram weight: {line}")
            if previous in vocabulary and word in vocabulary:
                pair = (previous, word)
                pairs[pair] = max(pairs.get(pair, 0), weight)
    for (previous, word), weight in sorted(source_pairs.items()):
        if previous in vocabulary and word in vocabulary:
            pair = (previous, word)
            pairs[pair] = max(pairs.get(pair, 0), weight)
    return pairs


def write_preserving_newlines(path, text):
    newline = b"\r\n" if path.exists() and b"\r\n" in path.read_bytes() else b"\n"
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8").replace(b"\n", newline))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("language", nargs="?", default="en_US")
    parser.add_argument("--commit", default=DEFAULT_COMMIT, help="Exact AOSP commit (40 hex digits)")
    parser.add_argument("--source", type=Path, help="Local combined text or gzip, from this language/commit")
    parser.add_argument("--notice-source", type=Path, help="Local repository NOTICE from the same commit")
    parser.add_argument("--output-dir", type=Path, default=ASSETS)
    args = parser.parse_args()
    if not re.fullmatch(r"[A-Za-z][A-Za-z_0-9]*", args.language):
        parser.error("Invalid language code")
    if not re.fullmatch(r"[0-9a-fA-F]{40}", args.commit):
        parser.error("--commit must be an exact 40-digit commit hash")

    data, url = load_source(args)
    if data.startswith(b"\x1f\x8b"):
        data = gzip.decompress(data)
    words, source_pairs = convert(data.decode("utf-8"))
    if not words:
        raise ValueError("Dictionary contains no eligible words")
    if args.language.split("_")[0] == "en":
        missing = {"a", "i", "am", "i'm", "don't", "it's"} - set(words)
        if missing:
            raise ValueError(f"Required English words missing: {sorted(missing)}")
    lang = args.language.split("_")[0]
    suffix = "" if lang == "en" else f"_{lang}"
    pairs = merge_bigrams(args.output_dir / f"bigrams{suffix}.txt", words, source_pairs)
    notice_url = f"{REPOSITORY}/+/{args.commit}/NOTICE?format=TEXT"
    upstream_notice = (args.notice_source.read_bytes() if args.notice_source
                       else download(notice_url)).decode("utf-8")
    if "Apache License" not in upstream_notice or "Version 2.0" not in upstream_notice:
        raise ValueError("Expected Apache 2.0 repository NOTICE")
    notice = (
        "TailorKeys dictionary attribution\n\n"
        "Project: Android Open Source Project (AOSP), LatinIME\n"
        f"Source URL: {url}\n"
        f"Source commit: {args.commit}\n"
        f"License/attribution source: {notice_url}\n"
        "License: Apache License, Version 2.0\n"
        "License URL: https://www.apache.org/licenses/LICENSE-2.0\n\n"
        "Modified by TailorKeys: lowercased, filtered, deduplicated and sorted by\n"
        "frequency, limited to 60,000 words; eligible AOSP bigrams rescaled to\n"
        "1-100 and merged with the existing TailorKeys pairs using maximum weights.\n"
        "This notice accompanies the generated words.txt and bigrams.txt assets.\n\n"
        "Original repository NOTICE (including the full Apache 2.0 license):\n"
        + upstream_notice.strip() + "\n"
    )
    args.output_dir.mkdir(parents=True, exist_ok=True)
    write_preserving_newlines(args.output_dir / f"words{suffix}.txt", "\n".join(words) + "\n")
    write_preserving_newlines(args.output_dir / f"bigrams{suffix}.txt", "".join(
        f"{previous} {word} {weight}\n" for (previous, word), weight in pairs.items()))
    notice_path = args.output_dir / "NOTICE-dictionary.txt"
    old = notice_path.read_text(encoding="utf-8") if notice_path.exists() else ""
    if suffix:
        marker = f"Additional AOSP dictionary: {lang}"
        block = (f"{marker}\nSource URL: {url}\nSource commit: {args.commit}\n"
                 f"License/attribution source: {notice_url}\n"
                 "License: Apache License 2.0 (full license and upstream NOTICE above).\n"
                 "Includes Dictionaries © Lexiteria LLC. Used by permission.\n"
                 "Modified by TailorKeys: Unicode letters and apostrophes retained, NFC\n"
                 "normalized, lowercased, filtered, deduplicated, frequency sorted;\n"
                 f"limited to 60,000 words. Covers words{suffix}.txt and AOSP seed pairs.\n")
        old = re.sub(re.escape(marker) + r"\n.*?(?=\n\n|\Z)", "", old, flags=re.S).rstrip()
        # A standalone output directory must still include the full upstream license.
        if not old:
            old = notice.rstrip()
        notice = old + "\n\n" + block
    else:
        extra = re.search(r"\n(?:Additional AOSP dictionary:|Tatoeba sentence n-gram attribution)", old)
        if extra:
            notice = notice.rstrip() + "\n\n" + old[extra.start():].lstrip()
    write_preserving_newlines(notice_path, notice)
    print(f"Source: {url}\nCommit: {args.commit}")
    print(f"Words: {len(words)}; source bigram pairs: {len(source_pairs)}; merged bigrams: {len(pairs)}")


if __name__ == "__main__":
    main()
