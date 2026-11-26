llama.cpp Android RPC Server (arm64-v8a • Vulkan GPU + OpenMP CPU)
==================================================================
To run directly on device via ADB:
  adb push ggml-rpc-server libomp.so /data/local/tmp/
  adb shell "chmod 755 /data/local/tmp/ggml-rpc-server"
  adb shell "export LD_LIBRARY_PATH=/data/local/tmp:\$LD_LIBRARY_PATH; /data/local/tmp/ggml-rpc-server -H 0.0.0.0 -p 50052"

To connect from PC host:
  adb forward tcp:50052 tcp:50052
  llama-cli.exe -m model.gguf --rpc 127.0.0.1:50052 -ngl 99 -p "Hello"
