"""Small semantic tests; no network or corpus/checkpoint required."""
import tempfile
from pathlib import Path
import unittest
import numpy as np
import torch
from eval import Baseline, make_row
from export import quantize
from model import Corpus, Model
from prepare import sentences


class PipelineTest(unittest.TestCase):
    def test_keyboard_tokens(self):
        self.assertEqual(list(sentences("I\u2019m HERE, with 1,234.5 things! Why? isn't_it 7.")),
                         [["i'm", "here", "<,>", "with", "<num>", "things"], ["why"], ["isn't", "it", "<num>"]])
        self.assertEqual(list(sentences("'hello' rock\u2018n\u2019roll... caf\u00e9")), [["hello", "rock'n'roll"], ["caf\u00e9"]])

    def test_context_target_alignment(self):
        row = dict(t=["i", "like", "tea"], c=[["hello"], ["yes"]])
        x, y, raw = make_row(row, {"i": 5, "like": 6, "tea": 7, "hello": 8, "yes": 9})
        self.assertEqual(x, [0, 8, 1, 0, 9, 1, 0, 5, 6, 7])
        self.assertEqual(y, [8, 1, 0, 9, 1, 0, 5, 6, 7, 1])
        self.assertEqual(raw, [None] * 6 + ["i", "like", "tea", None])

    def test_window_keeps_short_sentence_targets(self):
        corpus = Corpus.__new__(Corpus)
        r = np.asarray([100] + [0] * 100 + [0, 5, 6, 7, 1], dtype=np.uint16)
        corpus.parts = [("fixture", r, np.asarray([0, len(r)], dtype=np.int64))]
        for seed in range(5):
            x, y = corpus.record(0, 0, 64, np.random.default_rng(seed))
            self.assertEqual(len(x), 64)
            self.assertEqual(y[y != -100].tolist(), [5, 6, 7, 1])

    def test_baseline_tiers_and_prefix_score(self):
        with tempfile.TemporaryDirectory() as temp:
            p = Path(temp)
            (p / "words.txt").write_text("the\nto\na\nrare\ntiny\n")
            (p / "bigrams.txt").write_text("to the 100\nto rare 1\n")
            (p / "trigrams.txt").write_text("a to tiny 1\n")
            b = Baseline(p)
            self.assertEqual(b.predict("a", "to", ""), ("tiny", "the", "rare"))
            self.assertEqual(b.predict("a", "to", "t")[:2], ("the", "tiny"))
            self.assertEqual(b.predict("unseen", "to", ""), b.predict(None, "to", ""))
            self.assertEqual(b.predict("unseen", "unknown", "t"), b.predict(None, None, "t"))

    def test_quantization_rows(self):
        w = torch.tensor([[0., -1., 2.], [0., 0., 0.]])
        q, s = quantize(w)
        self.assertEqual(q.dtype, np.int8)
        self.assertTrue(np.all(s > 0))
        self.assertLessEqual(np.max(np.abs(q * s[:, None] - w.numpy())), 2 / 127)

    @unittest.skipUnless(torch.cuda.is_available(), "CUDA required for fused equation parity")
    def test_fused_cifg_outputs_and_gradients(self):
        torch.manual_seed(2)
        torch.backends.cuda.matmul.allow_tf32 = False
        torch.backends.cudnn.allow_tf32 = False
        cpu = Model(20, 8, 12)
        gpu = Model(20, 8, 12).cuda()
        gpu.load_state_dict(cpu.state_dict())
        x = torch.tensor([[0, 5, 7, 1], [0, 8, 6, 1]])
        a, ac = cpu(x)
        b, bc = gpu(x.cuda())
        torch.testing.assert_close(a, b.cpu(), atol=2e-6, rtol=1e-4)
        for c, d in zip(ac, bc):
            torch.testing.assert_close(c, d.cpu(), atol=2e-6, rtol=1e-4)
        a.square().sum().backward()
        b.square().sum().backward()
        for c, d in zip(cpu.parameters(), gpu.parameters()):
            torch.testing.assert_close(c.grad, d.grad.cpu(), atol=2e-6, rtol=1e-3)


if __name__ == "__main__":
    torch.set_num_threads(2)
    unittest.main()
