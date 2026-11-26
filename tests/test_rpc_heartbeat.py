#!/usr/bin/env python3
"""
tests/test_rpc_heartbeat.py
Verifies Silica Cluster core RPC v9 32-byte keyed handshakes, periodic heartbeats,
thermal load shedding, network audit telemetry, and zero-ghost-node unregister
against QEMU/Alpine Linux virtual machines (or local isolated namespace VMs).
"""

import time
import unittest

from cluster_test_lib import (
    ClusterVmTestHarness,
    compute_cluster_auth_token,
    http_json,
    probe_tcp_rpc_handshake,
)


class TestRpcHeartbeatAndDiscovery(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.harness = ClusterVmTestHarness()
        cls.harness.start()

    @classmethod
    def tearDownClass(cls):
        cls.harness.stop()

    def test_01_master_health_and_rpc_v9_beacon(self):
        st, health = http_json(self.harness.master_url, "/health")
        self.assertEqual(st, 200)
        self.assertEqual(health.get("status"), "ok")
        self.assertEqual(health.get("service"), "Silica Cluster")

        st, beacon = http_json(self.harness.master_url, "/api/cluster/beacon")
        self.assertEqual(st, 200)
        self.assertEqual(beacon.get("service"), "Silica-Cluster-Master")
        self.assertEqual(beacon.get("protocolVersion"), 9)
        self.assertEqual(beacon.get("rpcPort"), 50052)

    def test_02_32byte_keyed_auth_token_determinism(self):
        token_a = compute_cluster_auth_token(self.harness.cluster_secret)
        token_b = compute_cluster_auth_token(self.harness.cluster_secret)
        token_bad = compute_cluster_auth_token("wrong-secret")
        self.assertEqual(len(token_a), 32)
        self.assertEqual(token_a, token_b)
        self.assertNotEqual(token_a, token_bad)

    def test_03_direct_tcp_gguf_rpc_v9_handshake_against_alpine_vms(self):
        for ep in (self.harness.worker_1, self.harness.worker_2):
            ok, frame, rtt_ms = probe_tcp_rpc_handshake(
                ep.host, ep.port, self.harness.cluster_secret
            )
            self.assertTrue(ok, f"Expected valid GGUF/RPC v9 handshake from {ep.host}:{ep.port}")
            assert frame is not None
            self.assertEqual(frame["rpc_version"], 9)
            self.assertEqual(frame["version_tag"], "v1.0.0")
            self.assertEqual(frame["big_cores"], ep.big_cores)
            self.assertGreater(frame["safe_cap_mb"], 0)
            self.assertGreater(rtt_ms, 0.0)

        # Wrong secret against legitimate worker must be rejected
        ok_bad, frame_bad, _ = probe_tcp_rpc_handshake(
            self.harness.worker_1.host, self.harness.worker_1.port, "wrong-secret"
        )
        self.assertFalse(ok_bad)
        self.assertIsNone(frame_bad)

        # Rogue Alpine VM must fail handshake verification
        ok_rogue, frame_rogue, _ = probe_tcp_rpc_handshake(
            self.harness.rogue_worker.host,
            self.harness.rogue_worker.port,
            self.harness.cluster_secret,
        )
        self.assertFalse(ok_rogue)
        self.assertIsNone(frame_rogue)

    def test_04_periodic_heartbeat_and_rogue_worker_rejection(self):
        # Wait for periodic heartbeats from worker_1 & worker_2 to land on Master
        deadline = time.time() + 10.0
        found_ids: set[str] = set()
        while time.time() < deadline:
            st, hw = http_json(self.harness.master_url, "/api/cluster/hardware")
            self.assertEqual(st, 200)
            found_ids = {d["id"] for d in hw.get("connectedDevices", [])}
            if {self.harness.worker_1.worker_id, self.harness.worker_2.worker_id}.issubset(found_ids):
                break
            time.sleep(0.4)

        self.assertIn(self.harness.worker_1.worker_id, found_ids)
        self.assertIn(self.harness.worker_2.worker_id, found_ids)
        self.assertNotIn(self.harness.rogue_worker.worker_id, found_ids)

        # Explicitly attempting to register rogue VM must fail with HTTP 502
        st, rogue_resp = self.harness.register_worker(self.harness.rogue_worker)
        self.assertEqual(st, 502)
        self.assertFalse(rogue_resp.get("ok"))

    def test_05_heartbeat_updates_thermal_shedding_and_network_audit(self):
        # Activate 30B MoE so workers hold real layers before thermal spike
        st, _ = http_json(
            self.harness.master_url, "/api/models/activate", {"modelId": "moe-30b"}, method="POST"
        )
        self.assertEqual(st, 200)

        # Inject 49.5°C emergency thermal heartbeat from worker_2 (Android profile)
        st, reg = self.harness.register_worker(self.harness.worker_2, temp_c=49.5)
        self.assertEqual(st, 200)
        plan = reg["snapshot"]["plan"]
        w2_plan = next(n for n in plan["nodes"] if n["nodeId"] == self.harness.worker_2.worker_id)
        self.assertEqual(w2_plan["allocatedLayers"], 0)
        self.assertEqual(w2_plan["thermalStatus"]["state"], "EMERGENCY_OFFLOAD")

        # Network audit must include Master + verified Alpine workers with RPC port verified
        st, audit = http_json(self.harness.master_url, "/api/cluster/network/audit")
        self.assertEqual(st, 200)
        self.assertGreaterEqual(audit["summary"]["totalNodes"], 3)

        # Cool worker_2 back down so subsequent tests see nominal state
        st, cooled = self.harness.register_worker(self.harness.worker_2, temp_c=35.0)
        self.assertEqual(st, 200)
        w2_cool = next(
            n for n in cooled["snapshot"]["plan"]["nodes"]
            if n["nodeId"] == self.harness.worker_2.worker_id
        )
        self.assertEqual(w2_cool["thermalStatus"]["state"], "COOL")

    def test_06_immediate_unregister_leaves_zero_ghost_nodes(self):
        # Temporarily unregister worker_2 and verify immediate removal
        st, unreg = self.harness.unregister_worker(self.harness.worker_2.worker_id)
        self.assertEqual(st, 200)
        self.assertTrue(unreg.get("removed"))

        st, hw = http_json(self.harness.master_url, "/api/cluster/hardware")
        self.assertEqual(st, 200)
        ids_after = {d["id"] for d in hw.get("connectedDevices", [])}
        self.assertNotIn(self.harness.worker_2.worker_id, ids_after)

        # Re-register worker_2 cleanly for any downstream suite
        st, re_reg = self.harness.register_worker(self.harness.worker_2)
        self.assertEqual(st, 200)
        self.assertTrue(re_reg.get("ok"))


if __name__ == "__main__":
    unittest.main(verbosity=2)
