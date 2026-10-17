"""Export row-scaled signed int8 weights and independent PyTorch reference vectors."""
import argparse
import copy
import hashlib
import json
from pathlib import Path
import struct
import numpy as np
import torch
from model import load_checkpoint
from prepare import DEFAULT_DATA, ROOT, SPECIAL, sentences

MATRICES = ["embedding.weight", "wx", "wh", "projection"]
MAGIC = b"TKLMEN1\0"


def quantize(w):
    a = w.detach().cpu().float().numpy()
    scales = np.maximum(np.abs(a).max(axis=1), 1e-12) / 127
    q = np.clip(np.rint(a / scales[:, None]), -127, 127).astype(np.int8)
    return q, scales.astype("<f4")


def quantized_model(model):
    result = copy.deepcopy(model).cpu().float()
    with torch.no_grad():
        for name, p in result.named_parameters():
            if name in MATRICES:
                q, s = quantize(p)
                p.copy_(torch.from_numpy(q.astype(np.float32) * s[:, None]))
    return result


def export(args):
    torch.set_num_threads(4)
    model, ckpt = load_checkpoint(args.data / "checkpoints/best.pt")
    vocab = ckpt["vocab"]
    assert vocab[:5] == SPECIAL
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with open(args.output, "wb") as f:
        f.write(MAGIC)
        f.write(struct.pack("<4I", 1, len(vocab), model.config["embedding"], model.config["hidden"]))
        for word in vocab:
            encoded = word.encode("utf-8")
            f.write(struct.pack("<H", len(encoded)))
            f.write(encoded)
        params = dict(model.named_parameters())
        for name in MATRICES:
            q, scales = quantize(params[name])
            f.write(scales.tobytes())
            f.write(q.tobytes())
        for name in ("bias", "output_bias"):
            f.write(params[name].detach().numpy().astype("<f4").tobytes())
    size = args.output.stat().st_size
    if size > 5_000_000:
        raise ValueError(f"Model is too large: {size}")
    quant = quantized_model(model).eval()
    ids = {w: i for i, w in enumerate(vocab)}
    prompts = [
        "i would like to", "how are you", "thank you for your", "i don't know what to",
        "can you please help me", "i am going to the", "what do you think about",
        "it was a beautiful day", "i need to get", "we should go", "i love you",
        "are you sure you", "i have been working on", "i can't believe that",
        "i will call you", "would you like a", "let me know if you",
        "thanks, i will see you", "my phone number is 123", "hello",
        "the", "i", "you", "what", "why", "she said that", "we have a lot of",
        "i am not sure if", "i went to the store. i bought some", "hi! how are you? i am",
        "i thought we could meet tomorrow. would you like to have",
        "i'm sorry. i didn't mean to hurt your", "he said he was tired, but he kept",
        "i will be there in five", "do you want to come with", "i hope you have a great",
        "it's nice to meet", "please let me", "i'm looking forward to", "see you",
    ]
    refs, attempted, max_delta = [], 0, 0.0
    with torch.no_grad():
        for prompt in prompts:
            sequence = []
            parts = list(sentences(prompt))
            for i, tokens in enumerate(parts):
                if i:
                    sequence.append(1)
                sequence += [0] + [ids.get(t, 2) for t in tokens]
            x = torch.tensor([sequence])
            fp = model(x)[0][0, -1].log_softmax(-1)
            qp = quant(x)[0][0, -1].log_softmax(-1)
            top = fp.topk(10).indices
            attempted += 1
            max_delta = max(max_delta, float((fp[top] - qp[top]).abs().max()))
            # Exact rank tests are useful only where quantization does not reorder ties.
            margin = float((qp[top][:-1] - qp[top][1:]).min())
            if torch.equal(top, qp.topk(10).indices) and float((fp[top] - qp[top]).abs().max()) <= 0.12 and margin > 0.001:
                refs.append((sequence, top.tolist(), fp[top].tolist()))
    if len(refs) < 3:
        raise ValueError("Fewer than three stable reference sequences; inspect quantization")
    args.references.parent.mkdir(parents=True, exist_ok=True)
    sha = hashlib.sha256(args.output.read_bytes()).hexdigest()
    with open(args.references, "w", encoding="utf-8", newline="\n") as f:
        f.write("# PyTorch FLOAT32 checkpoint top-10; int8 rank-stable prompts; tolerance 0.15 nats\n")
        f.write(f"# sha256={sha}\n")
        for sequence, top, probabilities in refs[:12]:
            f.write(" ".join(map(str, sequence)) + "\t" + " ".join(map(str, top)) + "\t" + " ".join(f"{p:.8f}" for p in probabilities) + "\n")
    report = dict(bytes=size, sha256=sha, parameters=sum(p.numel() for p in model.parameters()), config=model.config, step=ckpt["step"], training_seconds=ckpt["seconds"], reference_candidates=attempted, stable_candidates=len(refs), references=min(12, len(refs)), max_top10_logprob_delta=max_delta)
    (args.data / "export.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--data", type=Path, default=DEFAULT_DATA)
    p.add_argument("--output", type=Path, default=ROOT / "app/src/main/assets/lm_en.bin")
    p.add_argument("--references", type=Path, default=ROOT / "app/src/test/resources/lm_reference.txt")
    export(p.parse_args())
