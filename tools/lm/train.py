"""Bounded-memory CUDA training with deterministic samples and held-out early stopping."""
import argparse
import json
import math
from pathlib import Path
import random
import time
import numpy as np
import torch
import torch.nn.functional as F
from model import Corpus, Model, collate, load_checkpoint
from prepare import DEFAULT_DATA


def save_checkpoint(checkpoint, path):
    temporary = path.with_suffix(".tmp")
    torch.save(checkpoint, temporary)
    temporary.replace(path)


@torch.no_grad()
def validate(model, corpus, device, limit=1024):
    model.eval()
    result = []
    for part, length in enumerate(corpus.lengths):
        total, count = 0.0, 0
        indexes = np.linspace(0, length - 1, min(limit, length), dtype=int)
        for start in range(0, len(indexes), 32):
            rows = [corpus.record(part, i, 160) for i in indexes[start:start + 32]]
            x, y = [t.to(device) for t in collate(rows)]
            with torch.autocast("cuda", dtype=torch.bfloat16):
                logits, _ = model(x)
                loss = F.cross_entropy(logits.flatten(0, 1), y.flatten(), ignore_index=-100, reduction="sum")
            total += loss.item()
            count += int((y != -100).sum())
        result.append(dict(source=corpus.parts[part][0], nll=total / count, tokens=count))
    # The held-out stopping objective uses the training source-token mixture.
    return result


def main(args):
    torch.set_num_threads(4)
    torch.manual_seed(args.seed)
    np.random.seed(args.seed)
    random.seed(args.seed)
    if not torch.cuda.is_available():
        raise RuntimeError("CUDA PyTorch is required for training")
    torch.backends.cuda.matmul.allow_tf32 = True
    torch.backends.cudnn.allow_tf32 = True
    device = "cuda"
    vocab = json.loads((args.data / "processed/vocab.json").read_text(encoding="utf-8"))
    stats = json.loads((args.data / "processed/stats.json").read_text())
    train, valid = Corpus(args.data, "train"), Corpus(args.data, "eval")
    model = Model(len(vocab)).to(device)
    optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
    ckpt_dir = args.data / "checkpoints"
    ckpt_dir.mkdir(exist_ok=True)
    step, best, stale = 0, float("inf"), 0
    previous_seconds = 0.0
    plateau_scale = 1.0
    if args.resume:
        model, ckpt = load_checkpoint(ckpt_dir / "last.pt", device)
        if ckpt["vocab"] != vocab:
            raise ValueError("Vocabulary changed since checkpoint; start a fresh run")
        optimizer = torch.optim.AdamW(model.parameters(), lr=args.lr, weight_decay=0.01)
        optimizer.load_state_dict(ckpt["optimizer"])
        step, best, stale = ckpt["step"], ckpt["best"], ckpt["stale"]
        previous_seconds = ckpt["seconds"]
        plateau_scale = ckpt.get("plateau_scale", 1.0)
    rng = np.random.default_rng(args.seed + step)
    if args.resume and "rng_state" in ckpt:
        rng.bit_generator.state = ckpt["rng_state"]
    source_weights = np.array([stats[s]["train_sampled_tokens"] for s, _, _ in train.parts], dtype=float)
    source_weights /= source_weights.sum()
    started = time.monotonic()
    print(json.dumps(dict(device=torch.cuda.get_device_name(), torch=torch.__version__, parameters=sum(p.numel() for p in model.parameters()), source_weights=source_weights.tolist())), flush=True)
    loss_sum, target_sum = 0.0, 0
    with open(args.data / "training.jsonl", "a", buffering=1) as log:
        while time.monotonic() - started + previous_seconds < args.minutes * 60:
            model.train()
            x, y = [t.to(device) for t in train.random_batch(args.batch, args.sequence, rng)]
            optimizer.zero_grad(set_to_none=True)
            # Warmup followed by a time-based cosine decay, with a nonzero floor.
            elapsed = time.monotonic() - started + previous_seconds
            factor = min(1.0, (step + 1) / 200) * (0.15 + 0.85 * (1 + math.cos(math.pi * min(1.0, elapsed / (args.minutes * 60)))) / 2)
            for group in optimizer.param_groups:
                group["lr"] = args.lr * factor * plateau_scale
            with torch.autocast("cuda", dtype=torch.bfloat16):
                logits, _ = model(x)
                loss = F.cross_entropy(logits.flatten(0, 1), y.flatten(), ignore_index=-100)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
            optimizer.step()
            targets = int((y != -100).sum())
            loss_sum += loss.item() * targets
            target_sum += targets
            step += 1
            if step % 100 == 0:
                print(f"step={step} seconds={elapsed:.1f} train_ppl={math.exp(loss_sum / target_sum):.3f} targets={target_sum} gpu_mb={torch.cuda.max_memory_allocated()/2**20:.0f}", flush=True)
                loss_sum, target_sum = 0.0, 0
            if step % args.validate_every == 0 or (args.max_steps and step >= args.max_steps):
                report = validate(model, valid, device, args.validation_sentences)
                nll = float(np.dot(source_weights, [r["nll"] for r in report]))
                improved = nll < best - 0.002
                best, stale = (nll, 0) if improved else (best, stale + 1)
                if not improved:
                    # Give smaller steps a chance before declaring a plateau final.
                    plateau_scale = max(0.05, plateau_scale * 0.5)
                row = dict(step=step, seconds=time.monotonic() - started + previous_seconds, nll=nll, perplexity=math.exp(nll), per_source=report, improved=improved, lr=optimizer.param_groups[0]["lr"], next_lr_scale=plateau_scale)
                print(json.dumps(row), flush=True)
                log.write(json.dumps(row) + "\n")
                checkpoint = dict(config=model.config, model=model.state_dict(), optimizer=optimizer.state_dict(), vocab=vocab, step=step, best=best, stale=stale, seconds=row["seconds"], seed=args.seed, rng_state=rng.bit_generator.state, args=vars(args) | {"data": str(args.data)})
                checkpoint["plateau_scale"] = plateau_scale
                save_checkpoint(checkpoint, ckpt_dir / "last.pt")
                if improved:
                    save_checkpoint(checkpoint, ckpt_dir / "best.pt")
                if stale >= args.patience or (args.max_steps and step >= args.max_steps):
                    break
        # Always evaluate/save the final state, including runs shorter than one interval.
        report = validate(model, valid, device, args.validation_sentences)
        nll = float(np.dot(source_weights, [r["nll"] for r in report]))
        row = dict(step=step, seconds=time.monotonic() - started + previous_seconds, nll=nll, perplexity=math.exp(nll), per_source=report, final=True)
        log.write(json.dumps(row) + "\n")
        print(json.dumps(row), flush=True)
        checkpoint = dict(config=model.config, model=model.state_dict(), optimizer=optimizer.state_dict(), vocab=vocab, step=step, best=min(best, nll), stale=stale, seconds=row["seconds"], seed=args.seed, rng_state=rng.bit_generator.state, args=vars(args) | {"data": str(args.data)})
        checkpoint["plateau_scale"] = plateau_scale
        save_checkpoint(checkpoint, ckpt_dir / "last.pt")
        if nll < best or not (ckpt_dir / "best.pt").exists():
            save_checkpoint(checkpoint, ckpt_dir / "best.pt")


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", type=Path, default=DEFAULT_DATA)
    p.add_argument("--minutes", type=float, default=90)
    p.add_argument("--batch", type=int, default=256)
    p.add_argument("--sequence", type=int, default=64)
    p.add_argument("--lr", type=float, default=0.002)
    p.add_argument("--validate-every", type=int, default=1000)
    p.add_argument("--validation-sentences", type=int, default=1024)
    p.add_argument("--patience", type=int, default=4)
    p.add_argument("--seed", type=int, default=1701)
    p.add_argument("--max-steps", type=int, default=0, help="Smoke tests only; 0 means unlimited")
    p.add_argument("--resume", action="store_true")
    main(p.parse_args())
