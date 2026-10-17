# TKLM English model format, version 1

All integers and IEEE-754 float32 values are **little-endian**, with no alignment,
padding, compression or checksum trailer. The complete asset must be <=5,000,000
bytes. Signed int8 uses two's complement; zero point is zero.

| Field | Encoding |
|---|---|
| Magic | 8 bytes: ASCII `TKLMEN1` followed by NUL |
| Format version | uint32 = 1 |
| Vocabulary size V | uint32 |
| Embedding/output-projection size D | uint32 |
| Recurrent hidden/cell size H | uint32 |
| Vocabulary, in ID order | Repeat V times: uint16 UTF-8 **byte** length, then those bytes |
| Tied embedding/output matrix E | Quantized matrix V x D |
| Input matrix Wx | Quantized matrix 3H x D |
| Recurrent matrix Wh | Quantized matrix 3H x H |
| Output projection P | Quantized matrix D x H |
| Cell gate bias b | 3H float32 values |
| Output bias a | V float32 values |

Every quantized matrix stores **all row scales first** (R positive float32 values),
followed by R*C int8 values in row-major order. No dimensions are repeated.
For each float matrix row, `scale = max(max(abs(row)), 1e-12) / 127` and
`q = clip(round-to-nearest-even(weight / scale), -127, 127)`.
`W[row,col] = q[row,col] * scale[row]`. Matrix multiplication accumulates
`sum(q[row,col] * x[col])` in float32, then multiplies by the row scale once.
Biases are not quantized. The embedding matrix appears only once because it is
tied to the output classifier.

Fixed special IDs: 0 `<s>`, 1 `</s>`, 2 `<unk>`, 3 `<num>`, 4 `<,>`.
All remaining IDs are lowercase dictionary words; apostrophes can be internal.
V1 readers reject unknown versions, malformed/truncated vocabularies, duplicate
words, nonfinite floats, nonpositive scales and trailing bytes. Reader dimension
bounds: 5<=V<=32000, 1<=D<=256, 1<=H<=1024, <=5M matrix elements.

Gate row order is **forget, candidate, output**, each H rows:

```text
x = E[wordId]
[zf, zg, zo] = Wx*x + Wh*h + b
f = sigmoid(zf)
c = f*c + (1-f)*tanh(zg)
h = sigmoid(zo)*tanh(c)
p = P*h
logits = E*p + a
logProbs = logits - logsumexp(logits)
```

Initial h and c are zero. `initialState()` represents no tokens; explicitly feed
`<s>` before predicting a sentence's first word. Feed `</s>` then `<s>` to retain
preceding sentence context, or reset and feed `<s>` for a new text stream. The
96-dimensional projection is used for output only; recurrence uses all H units.
There are no peepholes, layer normalization, projection bias or cell clipping.

`lm_reference.txt` has comment lines and tab-separated test records. Each record:
space-separated input IDs (including `<s>`), top-10 IDs in descending score order,
and ten natural-log probabilities from the original float32 PyTorch checkpoint.
The export script selects prompts whose top-10 ordering survives int8 rounding.
JVM tests require the exact ordering and <=0.15 nats difference from original
float probabilities. The comment SHA-256 binds vectors to the complete asset.
`export.json` outside the repository records the tested candidate count and
observed quantization error; corpus evaluation separately measures int8 weights.
