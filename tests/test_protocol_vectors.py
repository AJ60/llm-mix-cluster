#!/usr/bin/env python3
import json
import pathlib
import sys
import unittest

TESTS_DIR = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(TESTS_DIR))

from cluster_test_lib import decode_v10_reply, decode_v10_request, encode_v10_reply, encode_v10_request


class TestV10ProtocolVectors(unittest.TestCase):
    def setUp(self):
        raw = (TESTS_DIR / "fixtures" / "handshake_v10_vectors.json").read_text(encoding="utf-8")
        doc = json.loads(raw)
        self.vector = doc["vectors"][0]

    def test_request_vector_and_wrong_secret(self):
        nonce = bytes.fromhex(self.vector["nonce_hex"])
        request = encode_v10_request(self.vector["secret"], nonce)
        self.assertEqual(request.hex(), self.vector["request_hex"])
        self.assertEqual(decode_v10_request(self.vector["secret"], request), nonce)
        self.assertIsNone(decode_v10_request("wrong-secret", request))

    def test_reply_vector(self):
        nonce = bytes.fromhex(self.vector["nonce_hex"])
        reply = bytes.fromhex(self.vector["reply_hex"])
        decoded = decode_v10_reply(self.vector["secret"], reply, nonce)
        self.assertIsNotNone(decoded)
        self.assertEqual(decoded["rpc_version"], 10)
        self.assertEqual(decoded["safe_cap_mb"], 4096)
        self.assertEqual(decoded["big_cores"], 8)
        self.assertEqual(decoded["status"], "READY")
        self.assertEqual(encode_v10_reply(self.vector["secret"], nonce, 4096, 8, 64, 0), reply)


if __name__ == "__main__":
    unittest.main()
