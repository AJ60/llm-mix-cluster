#!/usr/bin/env python3
"""
tests/test_cluster_node.py
Primary end-to-end cluster node test suite and unified test runner for Silica Cluster
QEMU/Alpine Linux virtual machine verification.

Run all suites together:
  python3 tests/test_cluster_node.py -v
Or via standard unittest discovery:
  python3 -m unittest discover -s tests -p "test_*.py" -v
"""

import os
import sys
import unittest
from pathlib import Path

TESTS_DIR = Path(__file__).resolve().parent
if str(TESTS_DIR) not in sys.path:
    sys.path.insert(0, str(TESTS_DIR))

from cluster_test_lib import (
    ClusterVmTestHarness,
    build_synthetic_gguf_v3,
    http_json,
    probe_tcp_rpc_handshake,
)


class TestClusterNodeEndToEnd(unittest.TestCase):
    """End-to-end cluster node lifecycle across QEMU/Alpine VMs and Silica Master."""

    @classmethod
    def setUpClass(cls):
        cls.harness = ClusterVmTestHarness()
        cls.harness.start()

    @classmethod
    def tearDownClass(cls):
        cls.harness.stop()

    def test_01_end_to_end_rpc_handshake_and_multi_vm_gguf_sharding(self):
        # 1. Verify live TCP 32-byte keyed GGUF/RPC v9 handshake on both Alpine VMs
        for ep in (self.harness.worker_1, self.harness.worker_2):
            ok, frame, _ = probe_tcp_rpc_handshake(
                ep.host, ep.port, self.harness.cluster_secret
            )
            self.assertTrue(ok)
            assert frame is not None
            self.assertEqual(frame["rpc_version"], 9)
            self.assertEqual(frame["version_tag"], "v1.0.0")

            st, reg = self.harness.register_worker(ep)
            self.assertEqual(st, 200)
            self.assertTrue(reg.get("ok"))

        # 2. Load a synthetic 48-layer MoE + mmproj GGUF onto the multi-VM cluster
        model_path = self.harness.models_dir / "e2e-qwen3-30b-q4_k_m.gguf"
        mmproj_path = self.harness.models_dir / "e2e-qwen3-mmproj-f16.gguf"
        model_path.write_bytes(
            build_synthetic_gguf_v3("qwen3moe", "E2E-Qwen3-30B-A3B", 48, 65536, 128)
        )
        mmproj_path.write_bytes(b"\x00" * (1024 * 1024))

        st, inspect_res = http_json(
            self.harness.master_url,
            "/api/models/inspect",
            {"filePath": str(model_path)},
            method="POST",
        )
        self.assertEqual(st, 200)

        st, reload_res = http_json(
            self.harness.master_url,
            "/api/engine/reload",
            {"model": inspect_res["model"]},
            method="POST",
        )
        self.assertEqual(st, 200)
        plan = reload_res["snapshot"]["plan"]
        self.assertGreaterEqual(len(plan["nodes"]), 3)
        self.assertAlmostEqual(sum(plan["tensorSplit"]), 100.0, delta=0.1)
        self.assertIn("--mmproj", reload_res["cliArgs"])
        self.assertIn("--kv-unified", reload_res["cliArgs"])

    def test_02_ssrf_guard_blocks_internal_vm_subnet_and_metadata(self):
        for internal_url in (
            f"http://{self.harness.worker_1.host}:{self.harness.worker_1.port}/",
            "http://169.254.169.254/latest/meta-data/",
            "http://127.0.0.1:3000/health",
        ):
            st, resp = http_json(
                self.harness.master_url,
                "/api/tools/fetch-url",
                {"url": internal_url},
                method="POST",
            )
            self.assertEqual(st, 400)
            self.assertFalse(resp.get("ok"))

    def test_03_backend_os_scripts_and_openai_models_endpoint(self):
        st, models = http_json(self.harness.master_url, "/v1/models")
        self.assertEqual(st, 200)
        self.assertEqual(models.get("object"), "list")
        self.assertGreaterEqual(len(models.get("data", [])), 1)


def run_all_suites() -> int:
    loader = unittest.TestLoader()
    suite = loader.discover(start_dir=str(TESTS_DIR), pattern="test_*.py")
    verbosity = 2 if "-v" in sys.argv or "--verbose" in sys.argv else 1
    runner = unittest.TextTestRunner(verbosity=verbosity)
    result = runner.run(suite)
    return 0 if result.wasSuccessful() else 1


if __name__ == "__main__":
    mode = os.environ.get("CLUSTER_TEST_MODE", "local")
    master = os.environ.get("SILICA_MASTER_URL", "http://127.0.0.1:3000")
    print(f"[Silica VM Tests] mode={mode} master={master}", flush=True)
    sys.exit(run_all_suites())
