package com.example.service

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class RpcControlActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(this, RpcService::class.java).apply {
            action = RpcService.ACTION_AUTO_DETECT_MASTER
            putExtra(RpcService.EXTRA_CLUSTER_SECRET, "clusterd-default-hmac-key")
            putExtra(RpcService.EXTRA_FORCE_EMBEDDED_RPC, true)
            putExtra(RpcService.EXTRA_RPC_PORT, intent.getIntExtra(RpcService.EXTRA_RPC_PORT, 0))
        }
        startForegroundService(intent)
        finish()
    }
}
