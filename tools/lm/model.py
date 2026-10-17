"""One-layer, full-recurrent CIFG LSTM, output projection, tied word embeddings."""
import json
from pathlib import Path
import warnings
import numpy as np
import torch
from torch import nn
import torch.nn.functional as F

# The coupled four-gate tensors are deliberately rebuilt from three independent
# parameters each forward pass. cuDNN compacts them; the standard nn.LSTM warning
# is expected here, and output/gradient parity and memory use are tested.
warnings.filterwarnings("ignore", message="RNN module weights are not part of single contiguous chunk of memory.*", category=UserWarning)


@torch.jit.script
def recurrence(gates: torch.Tensor, wh: torch.Tensor, h: torch.Tensor, c: torch.Tensor):
    states = torch.jit.annotate(list[torch.Tensor], [])
    for t in range(gates.size(1)):
        f, g, o = (gates[:, t] + F.linear(h, wh)).chunk(3, -1)
        f = torch.sigmoid(f)
        c = f * c + (1.0 - f) * torch.tanh(g)
        h = torch.sigmoid(o) * torch.tanh(c)
        states.append(h)
    return torch.stack(states, 1), h, c


class Model(nn.Module):
    def __init__(self, vocab_size, embedding=96, hidden=672):
        super().__init__()
        self.config = dict(vocab_size=vocab_size, embedding=embedding, hidden=hidden)
        self.embedding = nn.Embedding(vocab_size, embedding)
        self.wx = nn.Parameter(torch.empty(3 * hidden, embedding))
        self.wh = nn.Parameter(torch.empty(3 * hidden, hidden))
        self.bias = nn.Parameter(torch.zeros(3 * hidden))
        self.projection = nn.Parameter(torch.empty(embedding, hidden))
        self.output_bias = nn.Parameter(torch.zeros(vocab_size))
        nn.init.normal_(self.embedding.weight, std=0.08)
        nn.init.xavier_uniform_(self.wx)
        nn.init.orthogonal_(self.wh)
        nn.init.xavier_uniform_(self.projection)
        with torch.no_grad():
            self.bias[:hidden].fill_(1.0)

    def forward(self, ids, state=None):
        emb = self.embedding(ids)
        if ids.is_cuda:
            # cuDNN's fused four-gate LSTM is an EXACT CIFG when input-gate
            # weights/biases are the negatives of the forget gate. Concatenation
            # preserves the coupling in autograd; only three gates are trained/exported.
            # Pin torch: _VF is the same private entry point used by nn.LSTM.
            xf, xg, xo = self.wx.chunk(3, 0)
            hf, hg, ho = self.wh.chunk(3, 0)
            bf, bg, bo = self.bias.chunk(3, 0)
            weights = [torch.cat((-xf, xf, xg, xo)), torch.cat((-hf, hf, hg, ho)),
                       torch.cat((-bf, bf, bg, bo)), torch.zeros(4 * self.config["hidden"], device=ids.device)]
            if state is None:
                shape = (1, ids.size(0), self.config["hidden"])
                initial = (emb.new_zeros(shape), emb.new_zeros(shape))
            else:
                initial = (state[0].unsqueeze(0), state[1].unsqueeze(0))
            hs, h, c = torch._VF.lstm(emb, initial, weights, True, 1, 0.0, self.training, False, True)
            h, c = h[0], c[0]
        else:
            gates = F.linear(emb, self.wx, self.bias)
            if state is None:
                shape = (ids.size(0), self.config["hidden"])
                state = (gates.new_zeros(shape), gates.new_zeros(shape))
            hs, h, c = recurrence(gates, self.wh.to(gates.dtype), state[0], state[1])
        projected = F.linear(hs, self.projection)
        logits = F.linear(projected, self.embedding.weight, self.output_bias)
        return logits, (h, c)


class Corpus:
    """Memory mapped variable-length records. All arrays have explicit endian widths."""
    def __init__(self, data, split):
        self.parts = []
        for source in ("common_voice", "tatoeba", "soda"):
            stem = Path(data) / "processed" / f"{source}.{split}"
            ids = np.memmap(str(stem) + ".ids", mode="r", dtype="<u2")
            offsets = np.memmap(str(stem) + ".offsets", mode="r", dtype="<i8")
            self.parts.append((source, ids, offsets))
        self.lengths = [len(o) - 1 for _, _, o in self.parts]
        self.ends = np.cumsum(self.lengths)

    def record(self, part, index, max_length=None, rng=None):
        _, ids, offsets = self.parts[part]
        r = ids[int(offsets[index]):int(offsets[index + 1])]
        context_len = int(r[0])
        seq = r[1:].astype(np.int64)
        x, y = seq[:-1].copy(), seq[1:].copy()
        y[:context_len] = -100  # Previous sentences warm the state, never count twice.
        if max_length and len(x) > max_length:
            # Drop oldest context first, retaining the complete current sentence.
            # Only sentences longer than the window need random target windows.
            first_end = context_len + max_length
            end = int(rng.integers(first_end, len(x) + 1)) if rng is not None and first_end < len(x) else len(x)
            x, y = x[end - max_length:end], y[end - max_length:end]
        return x, y

    def random_batch(self, batch_size, max_length, rng):
        rows = []
        for i in rng.integers(0, self.ends[-1], batch_size):
            part = int(np.searchsorted(self.ends, i, side="right"))
            index = int(i - (self.ends[part - 1] if part else 0))
            rows.append(self.record(part, index, max_length, rng))
        return collate(rows)


def collate(rows):
    length = max(len(x) for x, _ in rows)
    x = np.zeros((len(rows), length), dtype=np.int64)
    y = np.full_like(x, -100)
    for i, (a, b) in enumerate(rows):
        x[i, :len(a)], y[i, :len(b)] = a, b
    return torch.from_numpy(x), torch.from_numpy(y)


def load_checkpoint(path, device="cpu"):
    checkpoint = torch.load(path, map_location=device, weights_only=False)
    model = Model(**checkpoint["config"]).to(device)
    model.load_state_dict(checkpoint["model"])
    model.eval()
    return model, checkpoint
