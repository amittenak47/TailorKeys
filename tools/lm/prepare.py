"""Download only the allowlisted corpora; stream, deduplicate, split and encode them."""
import argparse
import bz2
from collections import Counter, deque
import gzip
import hashlib
import json
from pathlib import Path
import re
import sqlite3
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
DEFAULT_DATA = Path(Path(__file__).resolve().parents[3] / "tk-lm-data")
SPECIAL = ["<s>", "</s>", "<unk>", "<num>", "<,>"]
SOURCES = ["common_voice", "tatoeba", "soda"]
TOKEN = re.compile(r"[^\W\d_]+(?:'[^\W\d_]+)*|\d+(?:[.,]\d+)*|[,.!?]", re.UNICODE)


def sentences(text):
    """Unicode letters, internal apostrophes, decimal/grouped numbers, soft commas."""
    current = []
    for m in TOKEN.finditer(text.lower().replace("\u2019", "'").replace("\u2018", "'")):
        t = m.group()
        if t in ".!?":
            if current:
                yield current
                current = []
        else:
            current.append("<num>" if t[0].isdigit() else "<,>" if t == "," else t)
    if current:
        yield current


def digest(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for b in iter(lambda: f.read(1024 * 1024), b""):
            h.update(b)
    return h.hexdigest()


def get_json(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": "TailorKeys-LM"}), timeout=120) as r:
        return json.load(r)


def download(url, path):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        return
    tmp = path.with_suffix(path.suffix + ".partial")
    for attempt in range(5):
        try:
            print("download", url, flush=True)
            req = urllib.request.Request(url, headers={"User-Agent": "TailorKeys-LM"})
            with urllib.request.urlopen(req, timeout=180) as r, open(tmp, "wb") as f:
                while b := r.read(1024 * 1024):
                    f.write(b)
            tmp.replace(path)
            return
        except Exception:
            if attempt == 4:
                raise
            time.sleep(2 ** attempt)


def fetch(data):
    manifest_path = data / "manifest.json"
    if manifest_path.exists():
        manifest = json.loads(manifest_path.read_text())
    else:
        cv = get_json("https://api.github.com/repos/common-voice/common-voice/commits/main")["sha"]
        soda = get_json("https://huggingface.co/api/datasets/allenai/soda")["sha"]
        listing = get_json(f"https://api.github.com/repos/common-voice/common-voice/contents/server/data/en?ref={cv}")
        files = []
        for item in listing:
            name = item["name"]
            # Conservative: no Wikipedia-origin files, even inside Common Voice.
            if name.endswith(".txt") and not name.lower().startswith(("europarl-", "wiki")):
                files.append(dict(source="common_voice", path=f"raw/common_voice/{name}", url=item["download_url"]))
        files.append(dict(source="tatoeba", path="raw/eng_sentences.tsv.bz2", url="https://downloads.tatoeba.org/exports/per_language/eng/eng_sentences.tsv.bz2"))
        for split in ("train", "valid", "test"):
            files.append(dict(source="soda", path=f"raw/soda/{split}.parquet", url=f"https://huggingface.co/datasets/allenai/soda/resolve/{soda}/{split}.parquet"))
        manifest = dict(common_voice_revision=cv, soda_revision=soda, files=files)
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    for item in manifest["files"]:
        path = data / item["path"]
        download(item["url"], path)
        sha = digest(path)
        if "sha256" in item and sha != item["sha256"]:
            raise ValueError(f"Checksum mismatch: {path}")
        item["sha256"] = sha
        item["bytes"] = path.stat().st_size
        manifest_path.write_text(json.dumps(manifest, indent=2) + "\n")
    return manifest


def documents(data, manifest, source):
    for item in manifest["files"]:
        if item["source"] != source:
            continue
        path = data / item["path"]
        if source == "soda":
            import pyarrow.parquet as pq
            for batch in pq.ParquetFile(path).iter_batches(batch_size=256, columns=["dialogue"]):
                for turns in batch.column(0).to_pylist():
                    yield turns
        elif source == "tatoeba":
            with bz2.open(path, "rt", encoding="utf-8") as f:
                for line in f:
                    parts = line.rstrip("\n").split("\t", 2)
                    if len(parts) == 3 and parts[1] == "eng":
                        yield [parts[2]]
        else:
            with open(path, encoding="utf-8-sig") as f:
                for line in f:
                    yield [line]


def records(data, source, split):
    with gzip.open(data / "processed" / f"{source}.{split}.jsonl.gz", "rt", encoding="utf-8") as f:
        for line in f:
            yield json.loads(line)


def prepare(data, manifest, vocab_size):
    processed = data / "processed"
    processed.mkdir(exist_ok=True)
    # A disk B-tree bounds RAM independently of corpus size. Rebuild on rerun.
    seen_path = processed / "seen.sqlite"
    seen_path.unlink(missing_ok=True)
    db = sqlite3.connect(seen_path)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("PRAGMA cache_size=-32768")
    db.execute("CREATE TABLE seen (hash BLOB PRIMARY KEY) WITHOUT ROWID")
    allowed = set((ROOT / "app/src/main/assets/words.txt").read_text(encoding="utf-8").splitlines())
    counts, stats = Counter(), {}
    for source in SOURCES:
        stats[source] = dict(train_sentences=0, eval_sentences=0, train_tokens=0, eval_tokens=0, duplicates=0)
        outputs = {s: gzip.open(processed / f"{source}.{s}.jsonl.gz", "wt", encoding="utf-8", compresslevel=1) for s in ("train", "eval")}
        try:
            for doc in documents(data, manifest, source):
                history = deque(maxlen=2)
                last_split = None
                for turn in doc:
                    for tokens in sentences(turn):
                        h = hashlib.sha256(" ".join(tokens).encode()).digest()
                        split = "eval" if int.from_bytes(h[:8], "little") % 50 == 0 else "train"
                        if split != last_split:
                            history.clear()
                        last_split = split
                        if db.execute("INSERT OR IGNORE INTO seen VALUES (?)", (h,)).rowcount == 0:
                            stats[source]["duplicates"] += 1
                            # Duplicates must not become untracked training context.
                            history.clear()
                            continue
                        record = dict(t=tokens, c=list(history))
                        outputs[split].write(json.dumps(record, ensure_ascii=False, separators=(",", ":")) + "\n")
                        history.append(tokens)
                        stats[source][split + "_sentences"] += 1
                        stats[source][split + "_tokens"] += len(tokens) + 1
                        if split == "train":
                            counts.update(t for t in tokens if t in allowed)
                        total = stats[source]["train_sentences"] + stats[source]["eval_sentences"]
                        if total % 100000 == 0:
                            db.commit()
                            print(source, total, flush=True)
        finally:
            for f in outputs.values():
                f.close()
        db.commit()
        print(source, stats[source], flush=True)
    db.close()
    vocab = SPECIAL + [w for w, _ in sorted(counts.items(), key=lambda x: (-x[1], x[0]))[:vocab_size - len(SPECIAL)]]
    (processed / "vocab.json").write_text(json.dumps(vocab, ensure_ascii=False) + "\n", encoding="utf-8")
    # Retain all CV/Tatoeba, deterministically downsample SODA to the same target-token count.
    keep = min(1.0, (stats["common_voice"]["train_tokens"] + stats["tatoeba"]["train_tokens"]) / max(1, stats["soda"]["train_tokens"]))
    ids = {w: i for i, w in enumerate(vocab)}
    import numpy as np
    for source in SOURCES:
        for split in ("train", "eval"):
            n, target_tokens, sampled_sentences = 0, 0, 0
            with open(processed / f"{source}.{split}.ids", "wb") as out, open(processed / f"{source}.{split}.offsets", "wb") as offsets:
                offsets.write(np.asarray([0], dtype="<i8").tobytes())
                for row in records(data, source, split):
                    h = hashlib.sha256(" ".join(row["t"]).encode()).digest()
                    if source == "soda" and split == "train" and int.from_bytes(h[8:16], "little") / 2**64 >= keep:
                        continue
                    context = []
                    for previous in row["c"]:
                        context += [0] + [ids.get(w, 2) for w in previous] + [1]
                    current = [0] + [ids.get(w, 2) for w in row["t"]] + [1]
                    # Record: context-length uint16 then context/current uint16 IDs.
                    values = np.asarray([len(context)] + context + current, dtype="<u2")
                    out.write(values.tobytes())
                    n += len(values)
                    offsets.write(np.asarray([n], dtype="<i8").tobytes())
                    target_tokens += len(current) - 1
                    sampled_sentences += 1
            stats[source][split + "_sampled_tokens"] = target_tokens
            stats[source][split + "_sampled_sentences"] = sampled_sentences
    stats["soda_keep_probability"] = keep
    stats["vocab_size"] = len(vocab)
    stats["words_sha256"] = digest(ROOT / "app/src/main/assets/words.txt")
    (processed / "stats.json").write_text(json.dumps(stats, indent=2) + "\n")
    print(json.dumps(stats, indent=2), flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", type=Path, default=DEFAULT_DATA)
    p.add_argument("--vocab-size", type=int, default=15000)
    p.add_argument("--download-only", action="store_true")
    args = p.parse_args()
    args.data.mkdir(parents=True, exist_ok=True)
    manifest = fetch(args.data)
    if not args.download_only:
        prepare(args.data, manifest, args.vocab_size)
