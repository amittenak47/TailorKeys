"""Evaluate every held-out lexical target, including out-of-vocabulary misses."""
import argparse
from collections import defaultdict
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import time
import numpy as np
import torch
import torch.nn.functional as F
from export import quantized_model
from model import load_checkpoint
from prepare import DEFAULT_DATA, ROOT, SOURCES, records, digest


class Baseline:
    """Shipped English candidates(): no personal counts, no spelling corrections.

    Empty prefix: trigram/bigram/frequency tiers, then count, score and word.
    Known prefix: exact completions ranked by the existing logarithmic score.
    Only the three best frequency fallbacks per prefix can enter a top-three result.
    """
    def __init__(self, assets):
        words = list(dict.fromkeys((assets / "words.txt").read_text(encoding="utf-8").splitlines()))
        self.frequency = {w: 1.0 / (i + 10) for i, w in enumerate(words)}
        self.pairs, self.triples = defaultdict(dict), defaultdict(dict)
        for file, table, n in (("bigrams.txt", self.pairs, 2), ("trigrams.txt", self.triples, 3)):
            with open(assets / file, encoding="utf-8") as f:
                for line in f:
                    p = line.split()
                    if len(p) == n + 1 and all(w in self.frequency for w in p[:n]):
                        count = int(p[-1])
                        if count > 0:
                            key = p[0] if n == 2 else (p[0], p[1])
                            table[key][p[n - 1]] = count
        self.top = words[:300]
        self.fallback = defaultdict(list)
        for w in words:
            for length in (1, 2):
                p = w[:length]
                if len(self.fallback[p]) < 3:
                    self.fallback[p].append(w)

    def predict(self, prev2, prev, prefix):
        # Missing trigram contexts all share the same bigram calculation. Missing
        # bigrams share a unigram calculation. Canonicalize before caching rather
        # than retaining every distinct OOV name/context from the dialogue corpus.
        if (prev2, prev) not in self.triples:
            prev2 = None
            if prev not in self.pairs:
                prev = None
        return self._predict(prev2, prev, prefix)

    @lru_cache(maxsize=100000)
    def _predict(self, prev2, prev, prefix):
        pairs = self.pairs.get(prev, {})
        triples = self.triples.get((prev2, prev), {})
        pool = set(pairs) | set(triples)
        if prefix:
            pool = {w for w in pool if w.startswith(prefix)}
            pool.update(self.fallback.get(prefix, ()))
        else:
            # Exact top-three pruning of Predictor's tier ordering. Lower tiers
            # cannot enter if three trigram followers exist. Frequency fallbacks
            # beyond the first three can never enter any top-three result.
            if len(triples) >= 3:
                pool = set(triples)
            elif len(pool) < 3:
                pool.update(self.top[:3])

        def key(w):
            p, t = pairs.get(w, 0), triples.get(w, 0)
            score = math.log(self.frequency[w]) + 1.5 * (math.log1p(p) + 1.2 * math.log1p(t))
            if not prefix and prev is not None:
                return (-(2 if t else 1 if p else 0), -(t if t else p), -score, w)
            return (-score, w)
        return tuple(sorted(pool, key=key)[:3])


def make_row(row, ids):
    context = []
    for sentence in row["c"]:
        context += [0] + [ids.get(w, 2) for w in sentence] + [1]
    current = [0] + [ids.get(w, 2) for w in row["t"]] + [1]
    sequence = context + current
    targets = [None] * len(context) + row["t"] + [None]
    return sequence[:-1], sequence[1:], targets


@torch.no_grad()
def evaluate_source(model, data, source, ids, vocab, baseline, device, prefix_ids, limit=0, batch_size=32):
    # counts[model][prefix length][top1/top3]
    counts = {m: np.zeros((3, 2), dtype=np.int64) for m in ("lstm", "baseline")}
    lexical, oov, nll, ppl_tokens, seen = 0, 0, 0.0, 0, 0
    buffer = []

    def batch(rows):
        nonlocal lexical, oov, nll, ppl_tokens
        max_len = max(len(r[0]) for r in rows)
        state = None
        # Chunking also bounds softmax RAM for pathological long sentences.
        for offset in range(0, max_len, 64):
            length = min(64, max_len - offset)
            x = np.zeros((len(rows), length), dtype=np.int64)
            y = np.full_like(x, -100)
            lexical_positions, target_ids, raw_targets, contexts = [], [], [], []
            for b, (inputs, labels, raw) in enumerate(rows):
                end = min(offset + length, len(inputs))
                if end <= offset:
                    continue
                x[b, :end - offset] = inputs[offset:end]
                for pos in range(offset, end):
                    word = raw[pos]
                    if word is not None:
                        y[b, pos - offset] = labels[pos]
                        if not word.startswith("<"):
                            lexical_positions.append((b, pos - offset))
                            target_ids.append(labels[pos])
                            raw_targets.append(word)
                            # Today's predictor sees the previous two lexical words.
                            # Sentence-start context is reset; commas/numbers do not become words.
                            if baseline is not None:
                                start = pos - 1
                                previous = []
                                while start >= 0 and len(previous) < 2:
                                    before = raw[start]
                                    if before is None:
                                        break
                                    if not before.startswith("<"):
                                        previous.append(before)
                                    start -= 1
                                contexts.append((previous[1] if len(previous) > 1 else None, previous[0] if previous else None))
            tx = torch.from_numpy(x).to(device)
            logits, state = model(tx, state)
            ty = torch.from_numpy(y).to(device)
            # Perplexity is lexical + comma/number; EOS is excluded, like accuracy.
            nll += F.cross_entropy(logits.float().flatten(0, 1), ty.flatten(), ignore_index=-100, reduction="sum").item()
            ppl_tokens += int((y != -100).sum())
            if not lexical_positions:
                continue
            bp, tp = np.asarray(lexical_positions).T
            scores = logits[bp, tp].float()
            targets = torch.tensor(target_ids, device=device)
            known = targets >= 5
            target_score = scores.gather(1, targets[:, None])
            better = scores[:, 5:] > target_score
            # Resolve exact ties by vocabulary ID, reproducibly on all runtimes.
            better |= (scores[:, 5:] == target_score) & (torch.arange(5, len(vocab), device=device)[None, :] < targets[:, None])
            for k in range(3):
                if k:
                    groups = prefix_ids[k - 1]
                    ranks = (better & (groups[5:][None, :] == groups[targets][:, None])).sum(1)
                else:
                    ranks = better.sum(1)
                counts["lstm"][k, 0] += int(((ranks == 0) & known).sum())
                counts["lstm"][k, 1] += int(((ranks < 3) & known).sum())
            for word, (prev2, prev) in zip(raw_targets, contexts):
                for k in range(3):
                    best = baseline.predict(prev2, prev, word[:k] if k else "")
                    counts["baseline"][k, 0] += int(bool(best) and best[0] == word)
                    counts["baseline"][k, 1] += int(word in best)
            lexical += len(target_ids)
            oov += int((~known).sum())

    for row in records(data, source, "eval"):
        buffer.append(make_row(row, ids))
        seen += 1
        if len(buffer) == 4 * batch_size:
            # Sorting inside a small bounded buffer limits padding without retaining a corpus.
            buffer.sort(key=lambda r: len(r[0]))
            for start in range(0, len(buffer), batch_size):
                batch(buffer[start:start + batch_size])
            buffer.clear()
        if seen % 5000 == 0:
            print(source, "sentences", seen, "words", lexical, flush=True)
        if limit and seen >= limit:
            break
    if buffer:
        buffer.sort(key=lambda r: len(r[0]))
        for start in range(0, len(buffer), batch_size):
            batch(buffer[start:start + batch_size])
    return dict(sentences=seen, lexical_targets=lexical, oov=oov, nll=nll, ppl_tokens=ppl_tokens, perplexity=math.exp(nll / ppl_tokens), counts={m: c.tolist() for m, c in counts.items()})


def main(args):
    torch.set_num_threads(4)
    torch.backends.cuda.matmul.allow_tf32 = False
    torch.backends.cudnn.allow_tf32 = False
    device = "cuda" if torch.cuda.is_available() else "cpu"
    model, ckpt = load_checkpoint(args.data / "checkpoints/best.pt")
    if args.quantized:
        model = quantized_model(model)
    model.to(device).eval()
    vocab = ckpt["vocab"]
    ids = {w: i for i, w in enumerate(vocab)}
    prefix_ids = []
    for k in (1, 2):
        groups = {p: i for i, p in enumerate(sorted({w[:k] for w in vocab}))}
        prefix_ids.append(torch.tensor([groups[w[:k]] for w in vocab], device=device))
    asset_hashes = {name: digest(ROOT / "app/src/main/assets" / name) for name in ("words.txt", "bigrams.txt", "trigrams.txt")}
    heldout_hashes = {s: digest(args.data / "processed" / f"{s}.eval.jsonl.gz") for s in SOURCES}
    cached = None
    if args.baseline_cache:
        cached = json.loads(args.baseline_cache.read_text())
        if args.limit or cached.get("version") != 1 or cached.get("asset_hashes") != asset_hashes or cached.get("heldout_hashes") != heldout_hashes:
            raise ValueError("Baseline cache does not match these assets/full held-out corpus")
    baseline = None if cached else Baseline(ROOT / "app/src/main/assets")
    result = dict(quantized=args.quantized, checkpoint_step=ckpt["step"], limit=args.limit, sources={})
    started = time.monotonic()
    for source in SOURCES:
        result["sources"][source] = evaluate_source(model, args.data, source, ids, vocab, baseline, device, prefix_ids, args.limit, args.batch)
        if cached:
            r, b = result["sources"][source], cached["sources"][source]
            if r["lexical_targets"] != b["lexical_targets"] or r["sentences"] != b["sentences"]:
                raise ValueError("Baseline cache target count mismatch")
            r["counts"]["baseline"] = b["counts"]
        print(source, json.dumps(result["sources"][source]), flush=True)
    if not args.limit and not cached:
        cache = dict(version=1, asset_hashes=asset_hashes, heldout_hashes=heldout_hashes,
                     sources={s: dict(sentences=r["sentences"], lexical_targets=r["lexical_targets"], counts=r["counts"]["baseline"]) for s, r in result["sources"].items()})
        (args.data / "baseline-cache.json").write_text(json.dumps(cache, indent=2) + "\n")
    combined = dict(sentences=0, lexical_targets=0, oov=0, nll=0.0, ppl_tokens=0, counts={m: np.zeros((3, 2), dtype=np.int64) for m in ("lstm", "baseline")})
    for r in result["sources"].values():
        for key in ("sentences", "lexical_targets", "oov", "nll", "ppl_tokens"):
            combined[key] += r[key]
        for m in combined["counts"]:
            combined["counts"][m] += np.asarray(r["counts"][m])
    combined["counts"] = {m: c.tolist() for m, c in combined["counts"].items()}
    combined["perplexity"] = math.exp(combined["nll"] / combined["ppl_tokens"])
    result["sources"]["overall"] = combined
    result["seconds"] = time.monotonic() - started
    result["asset_hashes"] = asset_hashes
    result["heldout_hashes"] = heldout_hashes
    result["baseline_cache"] = str(args.baseline_cache) if cached else None
    output = args.data / ("evaluation_int8.json" if args.quantized else "evaluation.json")
    output.write_text(json.dumps(result, indent=2) + "\n")
    lines = ["# English next-word model results", "", f"Checkpoint step: {ckpt['step']}. Evaluation: {'row-quantized int8 weights dequantized in PyTorch' if args.quantized else 'float weights, float32 arithmetic'}.", "All held-out sentences are evaluated." if not args.limit else f"SMOKE TEST: first {args.limit} held-out sentences per source only.", "", "Accuracy counts lexical words, including OOV targets as misses. Special tokens are excluded from suggestions. Known prefixes include a whole word shorter than two letters. No personal learning or corrections. Overall is a micro-average of all held-out lexical targets (not source-balanced).", "", "The baseline reproduces the shipped empty-prefix trigram/bigram tiers, then count, logarithmic frequency/context score and lexical tie-break. Prefix results use exact completions ranked by the shipped score; fully typed words remain eligible. Previous lexical words reset at sentence boundaries. Existing n-gram assets may already contain held-out Tatoeba sentences, which favors the baseline.", "", "| Source | Words | OOV | PPL | Prefix | LSTM top-1 | Baseline top-1 | LSTM top-3 | Baseline top-3 |", "|---|---:|---:|---:|---|---:|---:|---:|---:|"]
    for source, r in result["sources"].items():
        n = r["lexical_targets"]
        for k in range(3):
            a, b = r["counts"]["lstm"][k], r["counts"]["baseline"][k]
            lines.append(f"| {source} | {n:,} | {100*r['oov']/n:.2f}% | {r['perplexity']:.2f} | {k} letters | {100*a[0]/n:.2f}% | {100*b[0]/n:.2f}% | {100*a[1]/n:.2f}% | {100*b[1]/n:.2f}% |")
    lines += ["", "Perplexity counts current-sentence tokens including commas/numbers, excluding EOS and context warmup. Training early stopping uses a separate deterministic subset and source weighting. Normalized sentences are deduplicated globally before the hash split; no held-out sentence is used as training context.", "", f"Evaluation elapsed: {result['seconds']:.1f} seconds. Full raw counts and provenance are saved outside the repository at `{output}`.", ""]
    export_path = args.data / "export.json"
    if export_path.exists():
        e = json.loads(export_path.read_text())
        lines += ["## Model and verification", "", f"Asset: **{e['bytes']:,} bytes** ({e['bytes']/1_000_000:.2f} MB), {e['parameters']:,} parameters; V={e['config']['vocab_size']}, D={e['config']['embedding']}, H={e['config']['hidden']}. SHA-256: `{e['sha256']}`.", ""]
        history_path = args.data / "training.jsonl"
        if history_path.exists():
            history = [json.loads(line) for line in history_path.read_text().splitlines() if line.strip()]
            if history:
                last = history[-1]
                lines += [f"Training path elapsed: {last['seconds']/60:.2f} minutes, {last['step']:,} updates; selected checkpoint {ckpt['step']:,}. Budget: {ckpt['args']['minutes']} minutes, with validation-driven LR halving and early stopping. Early-stopping perplexity includes EOS and uses source-balanced validation weights, unlike the lexical perplexity above.", ""]
        benchmark = ROOT / "app/build/reports/lm-benchmark.json"
        if benchmark.exists():
            b = json.loads(benchmark.read_text())
            if b.get("asset_sha256") == e["sha256"]:
                lines += [f"Host JVM {b['java']} ({b['arch']}), warmed medians: step **{b['step_median_ms']:.3f} ms**, step + full softmax **{b['step_softmax_median_ms']:.3f} ms**. Combined range across nine 100-call batches: {b['step_softmax_min_ms']:.3f}–{b['step_softmax_max_ms']:.3f} ms. Measured heap allocation: {b['allocated_bytes_per_step_softmax']:.2f} bytes/call (counter overhead subtracted). This is a desktop measurement, not proof of <3 ms on a mid-range tablet.", ""]
        lines += [f"Reference prompts: {e['references']} exported from the float32 checkpoint; {e['stable_candidates']}/{e['reference_candidates']} candidates retained exact top-10 ordering with a >0.001-nat adjacent margin and <=0.12-nat quantization drift. Maximum observed top-10 drift across all candidate prompts: {e['max_top10_logprob_delta']:.4f} nats. JVM tests require exact IDs and <=0.15 nats on the exported prompts.", "", "Swift mirrors the equations and format, uses Accelerate, and was manually reviewed. It cannot be compiled on this Windows host. Tablet/iOS latency remains unmeasured; nothing was installed on a connected device. Android debug assembly and JVM verification are recorded with the final commit.", ""]
    args.results.write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps(combined), flush=True)


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", type=Path, default=DEFAULT_DATA)
    p.add_argument("--results", type=Path, default=ROOT / "tools/lm/RESULTS.md")
    p.add_argument("--limit", type=int, default=0)
    p.add_argument("--quantized", action="store_true")
    p.add_argument("--baseline-cache", type=Path, help="Optional exact-asset/corpus-verified cache from a full evaluation")
    p.add_argument("--batch", type=int, default=32, help="Evaluation batch size; reduce for smaller GPUs")
    main(p.parse_args())
