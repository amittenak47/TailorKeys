#!/usr/bin/env python3
"""Build shared word n-grams from Tatoeba's per-language sentence export.

Usage: python tools/build_ngrams.py [eng] [--source eng_sentences.tsv.bz2]
Use --words and --output-dir for another language's vocabulary/assets. Downloads
and SQLite counts live in a temporary directory, never in the repository.
--source reuses a local download; supply its original --download-date for notices.
"""

import argparse
import bz2
from collections import Counter
from contextlib import closing
from datetime import datetime, timezone
import hashlib
from pathlib import Path
import re
import sqlite3
import tempfile
from urllib.request import urlopen

ASSETS = Path(__file__).resolve().parents[1] / "app/src/main/assets"
LIMIT = 150_000
MIN_COUNT = 3
# Freeze the 411 pre-corpus pairs, rather than reimporting generated counts on
# reruns (which would retain stale corpus counts). Other vocabularies filter them.
LEGACY_PAIRS = """of the 100
of you 75
of my 65
of your 65
of a 60
of it 55
of them 50
of this 45
thank you 90
see you 60
see the 50
see what 50
see if 45
see how 40
i have 80
i think 80
i know 80
i was 75
i will 75
i can 75
i don't 75
i love 75
i want 70
i need 70
i like 70
i just 65
i would 65
i hope 60
i feel 60
i did 55
i do 55
i mean 55
i really 50
i guess 50
i should 50
i could 50
i see 50
you are 90
you can 80
you have 80
you know 75
you want 70
you need 70
you were 65
you will 65
you should 65
you don't 65
you think 60
you like 60
you get 55
you do 55
you did 55
you feel 50
you mean 50
you could 50
you would 50
you so 45
you too 60
you soon 55
we are 85
we can 80
we have 80
we will 75
we should 70
we need 70
we were 65
we don't 65
we could 60
we would 60
we want 60
we just 55
we get 50
we go 50
we do 50
we had 50
we know 50
we all 50
they are 80
they have 75
they were 70
they can 70
they will 65
they don't 65
they want 60
they need 60
they said 55
they would 55
they do 50
they know 50
they had 50
they get 45
he is 80
he was 75
he said 70
he will 65
he can 60
he had 60
he did 55
he would 55
he doesn't 55
she is 80
she was 75
she said 70
she will 65
she can 60
she had 60
she did 55
she would 55
she doesn't 55
it is 90
it was 85
it will 80
it would 70
it can 65
it all 60
it doesn't 60
it out 50
it again 45
this is 90
this was 75
this time 65
this week 65
this morning 60
this year 60
this weekend 60
this one 55
that is 85
that was 80
that would 75
that you 65
that the 65
that we 55
there is 85
there are 80
there was 65
there were 60
there will 60
there for 55
there yet 50
there soon 50
to the 100
to be 95
to you 80
to do 80
to go 80
to get 80
to see 75
to have 75
to make 70
to know 70
to a 65
to me 65
to my 65
to your 65
to work 60
to take 60
to help 60
to say 60
to find 55
to come 55
in the 100
in a 85
in my 75
in your 75
in this 70
in it 60
in here 60
in there 55
in time 55
in bed 50
in love 60
in fact 55
in front 50
in our 55
in town 45
on the 100
on my 75
on your 75
on a 70
on this 65
on time 65
on it 60
on our 55
on monday 50
on friday 50
on saturday 45
on sunday 45
for the 95
for you 85
for a 80
for me 75
for your 70
for my 65
for this 65
for that 60
for now 60
for dinner 55
for sure 60
with the 90
with you 85
with a 75
with me 75
with my 70
with your 70
with this 65
with that 60
with them 55
at the 95
at home 80
at work 75
at a 65
at least 70
at all 65
at night 60
at my 55
from the 85
from you 75
from my 70
from a 65
from your 65
from here 60
from me 55
from home 50
as well 80
as a 75
as soon 75
as you 65
as the 65
as much 60
as long 60
as always 50
and the 85
and i 85
and you 75
and then 70
and a 65
and it 65
and we 60
and my 60
and that 55
and have 55
and get 50
and see 50
but i 80
but you 70
but it 65
but the 60
but we 55
but not 55
or not 70
or something 60
or you 55
or the 50
or a 50
not sure 75
not a 70
not the 65
not to 65
not going 65
not really 60
not yet 60
not be 55
going to 70
going on 60
going home 55
going out 55
going well 50
want to 85
want a 60
want you 55
want the 50
need to 85
need a 70
need help 65
need you 55
have to 90
have a 85
have been 85
have you 75
have the 65
have some 60
have fun 65
have time 60
had a 75
had to 75
had been 70
had the 60
will be 90
will do 75
will have 70
will get 70
will see 65
will take 60
would be 90
would like 85
would have 80
would you 75
would love 70
can you 90
can be 80
can i 80
can we 75
can do 70
can get 65
could you 85
could be 80
could have 70
could i 65
should be 85
should have 75
should we 70
should i 70
do you 95
do not 80
do it 75
do the 65
do we 60
do i 60
did you 90
did not 80
did it 70
did the 60
don't know 90
don't worry 85
don't have 75
don't want 75
don't think 70
don't forget 65
what is 90
what are 85
what do 85
what you 75
what about 75
what time 70
how are 90
how much 80
how do 80
how to 80
how was 75
how many 70
when you 80
when i 80
when we 70
when the 65
where are 80
where is 80
where you 65
where do 65
if you 90
if i 80
if we 75
if it 70
because i 75
because of 80
because you 65
because it 65
let me 90
get the 75
get a 75
get back 75
get some 65
get home 65
get it 65
come back 80
come home 70
come over 65
come on 70
take care 85
take a 80
take the 75
take it 65
tell me 85
tell you 75
make sure 85
make it 75
make a 70
look at 85
look like 75
look for 65
good morning 90
good night 90
good luck 80
good idea 75
good to 70
happy birthday 90
happy to 70
love you 95
love it 75
love to 70
thanks for 90
thanks again 70
sorry for 80
sorry about 75
very good 75
very much 75
very nice 65
very happy 60
very sorry 60
so much 80
so good 70
so sorry 70
so happy 65
so far 65
right now 85
right here 75
right there 65
right away 60
be there 80
be a 75
be here 65
be back 65"""


def write_preserving_newlines(path, text):
    newline = b"\r\n" if path.exists() and b"\r\n" in path.read_bytes() else b"\n"
    path.write_bytes(text.replace("\r\n", "\n").encode("utf-8").replace(b"\n", newline))


def tokens(sentence):
    """Letters and straight apostrophes only; None marks a sentence boundary."""
    word = []
    for ch in sentence.lower():
        if ch.isalpha() or ch == "'":
            word.append(ch)
        else:
            if word:
                yield "".join(word)
                word.clear()
            if ch in ".!?\r\n":
                yield None
    if word:
        yield "".join(word)


def count_ngrams(source, language, vocabulary, db):
    batch = Counter()
    sentences = pairs = triples = 0

    def flush():
        db.executemany("INSERT INTO counts VALUES (?, ?, ?) "
                       "ON CONFLICT(n, words) DO UPDATE SET count=count+excluded.count",
                       ((n, words, count) for (n, words), count in batch.items()))
        db.commit()
        batch.clear()

    with bz2.open(source, "rt", encoding="utf-8") as stream:
        for line in stream:
            fields = line.rstrip("\r\n").split("\t", 2)
            if len(fields) != 3 or fields[1] != language:
                continue
            sentences += 1
            prev2 = prev = None  # Never connect separate TSV records.
            for word in tokens(fields[2]):
                if word is None or word not in vocabulary:
                    prev2 = prev = None  # Never bridge an out-of-vocabulary word.
                    continue
                if prev is not None:
                    batch[2, f"{prev} {word}"] += 1
                    pairs += 1
                if prev2 is not None:
                    batch[3, f"{prev2} {prev} {word}"] += 1
                    triples += 1
                prev2, prev = prev, word
            if len(batch) >= 50_000:
                flush()
            if sentences % 250_000 == 0:
                print(f"Counted {sentences:,} sentences", flush=True)
    flush()
    return sentences, pairs, triples


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("language", nargs="?", default="eng")
    parser.add_argument("--source", type=Path)
    parser.add_argument("--words", type=Path, default=ASSETS / "words.txt")
    parser.add_argument("--output-dir", type=Path, default=ASSETS)
    parser.add_argument("--download-date", default=datetime.now(timezone.utc).date().isoformat())
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z]{3}", args.language):
        parser.error("language must be a three-letter Tatoeba language code")
    datetime.strptime(args.download_date, "%Y-%m-%d")
    url = (f"https://downloads.tatoeba.org/exports/per_language/{args.language}/"
           f"{args.language}_sentences.tsv.bz2")
    vocabulary = set(args.words.read_text(encoding="utf-8").splitlines())
    args.output_dir.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="tailorkeys-counts-") as work:
        source = args.source
        if source is None:
            source = Path(work) / f"{args.language}_sentences.tsv.bz2"
            with urlopen(url, timeout=60) as response, source.open("wb") as output:
                while chunk := response.read(1024 * 1024):
                    output.write(chunk)
        with source.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        print(f"Source: {url}\nDownload date (UTC): {args.download_date}\nSHA-256: {digest}", flush=True)
        with closing(sqlite3.connect(Path(work) / "counts.sqlite")) as db:
            db.execute("PRAGMA journal_mode=OFF")
            db.execute("PRAGMA synchronous=OFF")
            db.execute("PRAGMA cache_size=-8192")
            db.execute("PRAGMA temp_store=FILE")
            db.execute("CREATE TABLE counts (n INTEGER, words TEXT, count INTEGER, "
                       "PRIMARY KEY(n, words)) WITHOUT ROWID")
            stats = count_ngrams(source, args.language, vocabulary, db)
            eligible = [db.execute("SELECT count(*) FROM counts WHERE n=? AND count>=?",
                                   (n, MIN_COUNT)).fetchone()[0] for n in (2, 3)]
            for line in LEGACY_PAIRS.splitlines():
                prev, word, count = line.split()
                if prev in vocabulary and word in vocabulary:
                    db.execute("INSERT INTO counts VALUES (2, ?, ?) "
                               "ON CONFLICT(n, words) DO UPDATE SET count=max(count, excluded.count)",
                               (f"{prev} {word}", int(count)))
            db.commit()
            for n, name in ((2, "bigrams.txt"), (3, "trigrams.txt")):
                rows = db.execute("SELECT words, count FROM counts WHERE n=? AND count>=? "
                                  "ORDER BY count DESC, words ASC LIMIT ?", (n, MIN_COUNT, LIMIT))
                path = args.output_dir / name
                newline = "\r\n" if path.exists() and b"\r\n" in path.read_bytes() else "\n"
                lines = 0
                with path.open("w", encoding="utf-8", newline=newline) as output:
                    for words, count in rows:
                        output.write(f"{words} {count}\n")
                        lines += 1
                print(f"{name}: {lines:,} lines; {path.stat().st_size:,} bytes")
    notice_path = args.output_dir / "NOTICE-dictionary.txt"
    marker = "Tatoeba sentence n-gram attribution"
    notice = notice_path.read_text(encoding="utf-8") if notice_path.exists() else ""
    notice = notice.split(marker)[0].rstrip() + "\n\n"
    notice += (f"{marker}\n\nProject: Tatoeba contributors, {args.language} sentences\n"
               "Project URL: https://tatoeba.org/\n"
               f"Source URL: {url}\nDownload date (UTC): {args.download_date}\n"
               f"Source SHA-256: {digest}\n"
               "License: Creative Commons Attribution 2.0 France (CC BY 2.0 FR)\n"
               "License URL: https://creativecommons.org/licenses/by/2.0/fr/\n"
               "Export documentation: https://tatoeba.org/en/downloads\n\n"
               "Modified by TailorKeys: lowercased and tokenized into letters/apostrophes;\n"
               "counted adjacent vocabulary-only pairs/triples within sentences; retained\n"
               "counts >= 3, limited each asset to the highest-count 150,000 entries.\n"
               "Existing TailorKeys pair weights merged using the maximum.\n"
               "This attribution covers bigrams.txt and trigrams.txt.\n")
    write_preserving_newlines(notice_path, notice)
    print(f"Sentences: {stats[0]:,}; vocabulary pair/triple occurrences: {stats[1]:,}/{stats[2]:,}")
    print(f"Corpus pairs/triples with count >= {MIN_COUNT}: {eligible[0]:,}/{eligible[1]:,}")


if __name__ == "__main__":
    main()
