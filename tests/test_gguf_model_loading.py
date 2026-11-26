#!/usr/bin/env python3
"""
tests/test_gguf_model_loading.py
Verifies Silica Cluster binary GGUF v3 header parsing, companion `*mmproj*.gguf`
auto-pairing, Zero-OOM multi-VM sharding invariants, stock llama.cpp v1.0.0 CLI
flag generation, draft vocab-hash mismatch rejection, and honest HTTP 503 offline mode.
"""

import unittest

from cluster_test_lib import (
    ClusterVmTestHarness,
    build_synthetic_gguf_v3,
    http_json,
)


class TestGgufModelLoading(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.harness = ClusterVmTestHarness()
        cls.harness.start()
        # Ensure both legitimate Alpine VMs are registered for multi-node sharding tests
        st1, _ = cls.harness.register_worker(cls.harness.worker_1)
        st2, _ = cls.harness.register_worker(cls.harness.worker_2)
        if st1 != 200 or st2 != 200:
            raise RuntimeError(f"Failed to register Alpine VMs: w1={st1}, w2={st2}")

    @classmethod
    def tearDownClass(cls):
        cls.harness.stop()

    def test_01_inspect_dense_and_moe_gguf_headers_with_mmproj_pairing(self):
        models_dir = self.harness.models_dir

        dense_path = models_dir / "llama3-8b-q4_k_m.gguf"
        dense_path.write_bytes(
            build_synthetic_gguf_v3(
                arch="llama",
                name="Llama-3-8B-Instruct",
                blocks=32,
                context_length=8192,
                experts=None,
                tokens=("<bos>", "llama", "dense"),
            )
        )

        moe_path = models_dir / "qwen3-vl-30b-q4_k_m.gguf"
        mmproj_path = models_dir / "qwen3-mmproj-f16.gguf"
        moe_path.write_bytes(
            build_synthetic_gguf_v3(
                arch="qwen3moe",
                name="Qwen3-VL-30B-A3B",
                blocks=48,
                context_length=65536,
                experts=128,
                tokens=("<bos>", "qwen", "vision", "moe"),
            )
        )
        mmproj_path.write_bytes(b"\x00" * (2 * 1024 * 1024))

        # 1. Inspect Dense GGUF
        st, dense_res = http_json(
            self.harness.master_url,
            "/api/models/inspect",
            {"filePath": str(dense_path)},
            method="POST",
        )
        self.assertEqual(st, 200)
        self.assertTrue(dense_res.get("ok"))
        self.assertEqual(dense_res["model"]["architecture"], "Dense")
        self.assertEqual(dense_res["model"]["layers"], 32)

        # 2. Inspect MoE Vision GGUF + companion mmproj auto-pairing
        st, moe_res = http_json(
            self.harness.master_url,
            "/api/models/inspect",
            {"filePath": str(moe_path)},
            method="POST",
        )
        self.assertEqual(st, 200)
        self.assertTrue(moe_res.get("ok"))
        card = moe_res["model"]
        self.assertEqual(card["architecture"], "MoE")
        self.assertEqual(card["layers"], 48)
        self.assertEqual(card["expertCount"], 128)
        self.assertIsNotNone(card.get("mmprojPath"))
        self.assertIn("mmproj", card["mmprojPath"])
        self.assertTrue(card.get("vocabHash"))

        # 3. Directory scan ignores standalone mmproj files while returning real models
        st, scan_res = http_json(
            self.harness.master_url, f"/api/local/models?dir={models_dir}"
        )
        self.assertEqual(st, 200)
        names = [m["name"] for m in scan_res.get("models", [])]
        self.assertGreaterEqual(scan_res.get("count", 0), 2)
        self.assertTrue(all("mmproj" not in n.lower() for n in names))

    def test_02_reject_corrupt_non_gguf_and_missing_files(self):
        bad_ext = self.harness.models_dir / "not-a-model.bin"
        bad_ext.write_bytes(b"random-bytes")

        st, _ = http_json(
            self.harness.master_url,
            "/api/models/inspect",
            {"filePath": str(bad_ext)},
            method="POST",
        )
        self.assertEqual(st, 400)

        st, _ = http_json(
            self.harness.master_url,
            "/api/models/inspect",
            {"filePath": str(self.harness.models_dir / "does-not-exist.gguf")},
            method="POST",
        )
        self.assertEqual(st, 404)

    def test_03_hot_reload_sharding_invariants_and_stock_llama_cli_flags(self):
        moe_path = self.harness.models_dir / "qwen3-vl-30b-q4_k_m.gguf"
        mmproj_path = self.harness.models_dir / "qwen3-mmproj-f16.gguf"
        if not moe_path.exists():
            moe_path.write_bytes(
                build_synthetic_gguf_v3("qwen3moe", "Qwen3-VL-30B-A3B", 48, 65536, 128)
            )
            mmproj_path.write_bytes(b"\x00" * (2 * 1024 * 1024))

        st, inspected = http_json(
            self.harness.master_url,
            "/api/models/inspect",
            {"filePath": str(moe_path)},
            method="POST",
        )
        self.assertEqual(st, 200)

        st, reloaded = http_json(
            self.harness.master_url,
            "/api/engine/reload",
            {"model": inspected["model"]},
            method="POST",
        )
        self.assertEqual(st, 200)
        self.assertTrue(reloaded.get("ok"))

        cli_args = reloaded["cliArgs"]
        self.assertIn("--mmproj", cli_args)
        self.assertIn("--jinja", cli_args)
        self.assertIn("--kv-unified", cli_args)
        self.assertIn("--tensor-split", cli_args)

        plan = reloaded["snapshot"]["plan"]
        self.assertAlmostEqual(sum(plan["tensorSplit"]), 100.0, delta=0.1)

        total_layers = 0
        for node in plan["nodes"]:
            safe_cap = node["safeRamMb"] + node["safeVramMb"]
            self.assertLessEqual(
                node["allocatedMb"],
                safe_cap + 1,
                f"Zero-OOM violated on {node['nodeId']}: {node['allocatedMb']} > {safe_cap}",
            )
            if node["platform"] == "Android":
                self.assertLessEqual(node["safeRamMb"], node["totalRamMb"] * 0.5 + 1)
            total_layers += node["allocatedLayers"]

        self.assertEqual(total_layers, inspected["model"]["layers"])

    def test_04_reject_speculative_draft_with_mismatched_vocab_hash(self):
        target_path = self.harness.models_dir / "target-qwen3-q4_k_m.gguf"
        draft_path = self.harness.models_dir / "mismatched-draft-q4_k_m.gguf"
        target_path.write_bytes(
            build_synthetic_gguf_v3(
                "qwen3moe", "Target-Qwen3", 48, 32768, 128, tokens=("<bos>", "alpha", "beta")
            )
        )
        draft_path.write_bytes(
            build_synthetic_gguf_v3(
                "llama", "Wrong-Vocab-Draft", 16, 8192, None, tokens=("<bos>", "totally", "different")
            )
        )

        st, inspected = http_json(
            self.harness.master_url,
            "/api/models/inspect",
            {"filePath": str(target_path)},
            method="POST",
        )
        self.assertEqual(st, 200)

        st, reload_err = http_json(
            self.harness.master_url,
            "/api/engine/reload",
            {
                "model": inspected["model"],
                "tuningConfig": {
                    "speculativeMode": "external_draft",
                    "draftModelPath": str(draft_path),
                },
            },
            method="POST",
        )
        self.assertEqual(st, 400)
        self.assertIn("vocab", reload_err.get("error", "").lower())

    def test_05_honest_http_503_when_no_live_engine_is_loaded(self):
        http_json(
            self.harness.master_url, "/api/cluster/state", {"upstreamBaseUrl": ""}, method="POST"
        )
        st, body = http_json(
            self.harness.master_url,
            "/v1/chat/completions",
            {
                "model": "small-1.5b",
                "messages": [{"role": "user", "content": "Verify offline 503"}],
            },
            method="POST",
        )
        self.assertEqual(st, 503)
        self.assertEqual(body.get("error", {}).get("code"), "ENGINE_OFFLINE")


if __name__ == "__main__":
    unittest.main(verbosity=2)
