#define RPC_SERVER_LIBRARY_ONLY 1
#include "rpc_server.cpp"

#include <cassert>
#include <cstdlib>
#include <iostream>

int main() {
    using namespace silica;

    // 1. Device lacks VK_KHR_16bit_storage -> GPU backend explicitly disabled, ARMv8.0-A NEON multi-threaded CPU
    {
        VulkanPhysicalDeviceProbe probe;
        probe.vulkan_library_loaded = true;
        probe.physical_device_found = true;
        probe.device_name = "Adreno (TM) 619";
        probe.api_version = VK_MAKE_API_VERSION(0, 1, 2, 0);
        probe.device_type = VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
        probe.max_push_constants_size = 256;
        probe.max_compute_work_group_invocations = 512;
        probe.has_compute_queue = true;
        probe.has_khr_16bit_storage_ext = false;
        probe.storage_buffer_16bit_access = false;

        const RpcBackendConfig cfg = evaluate_vulkan_physical_device(probe, 6, false);
        assert(!cfg.gpu_backend_enabled);
        assert(cfg.mode == RpcBackendMode::ARMV8_0_NEON_CPU);
        assert(cfg.n_gpu_layers == 0);
        assert(cfg.device_arg == "CPU");
        assert(cfg.cpu_isa_target == "ARMv8.0-A NEON");
        assert(cfg.cpu_threads >= 1);
        assert(cfg.reason.find("VK_KHR_16bit_storage") != std::string::npos);

        apply_rpc_backend_environment(cfg);
        assert(std::string(std::getenv("GGML_VK_VISIBLE_DEVICES")).empty());
        assert(std::string(std::getenv("GGML_VK_DISABLE_F16")) == "1");
        assert(std::string(std::getenv("SILICA_GPU_BACKEND_DISABLED")) == "1");
        assert(std::string(std::getenv("SILICA_ZERO_FALLBACK_CPU")) == "1");
        assert(std::string(std::getenv("GGML_ARM_DISABLE_DOTPROD")) == "1");
        assert(std::string(std::getenv("GGML_ARM_DISABLE_I8MM")) == "1");
        assert(std::string(std::getenv("SILICA_CPU_EXEC_PATH")) == "ARMv8.0-A NEON");
        assert(std::string(std::getenv("OMP_PROC_BIND")) == "close");
        assert(std::string(std::getenv("OMP_PLACES")) == "cores");
        assert(std::getenv("GGML_NUM_THREADS") != nullptr);
        assert(std::getenv("SILICA_NEON_THREADS") != nullptr);

        const std::string report = format_probe_report(cfg);
        assert(report.find("rpc_backend_zero_fallback_cpu=1") != std::string::npos);
    }

    // 2. Device has VK_KHR_16bit_storage BUT low maxPushConstantsSize (128 < 256) -> GPU backend explicitly disabled
    {
        VulkanPhysicalDeviceProbe probe;
        probe.vulkan_library_loaded = true;
        probe.physical_device_found = true;
        probe.device_name = "Mali-G76";
        probe.api_version = VK_MAKE_API_VERSION(0, 1, 2, 0);
        probe.device_type = VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
        probe.max_push_constants_size = 128;
        probe.max_compute_work_group_invocations = 512;
        probe.has_compute_queue = true;
        probe.has_khr_16bit_storage_ext = true;
        probe.storage_buffer_16bit_access = true;

        const RpcBackendConfig cfg = evaluate_vulkan_physical_device(probe, 4, false);
        assert(!cfg.gpu_backend_enabled);
        assert(cfg.mode == RpcBackendMode::ARMV8_0_NEON_CPU);
        assert(cfg.n_gpu_layers == 0);
        assert(cfg.device_arg == "CPU");
        assert(cfg.cpu_isa_target == "ARMv8.0-A NEON");
        assert(cfg.reason.find("maxPushConstantsSize=128") != std::string::npos);

        const std::string report = format_probe_report(cfg);
        assert(report.find("maxPushConstantsSize=128") != std::string::npos);
        assert(report.find("rpc_backend_gpu_enabled=0") != std::string::npos);
        assert(report.find("ARMv8.0-A NEON") != std::string::npos);
    }

    // 3. Software Vulkan device (CPU / SwiftShader) -> GPU backend explicitly disabled
    {
        VulkanPhysicalDeviceProbe probe;
        probe.vulkan_library_loaded = true;
        probe.physical_device_found = true;
        probe.device_name = "SwiftShader Device (LLVM 10.0.0)";
        probe.api_version = VK_MAKE_API_VERSION(0, 1, 3, 0);
        probe.device_type = VK_PHYSICAL_DEVICE_TYPE_CPU;
        probe.max_push_constants_size = 256;
        probe.has_khr_16bit_storage_ext = true;
        probe.storage_buffer_16bit_access = true;

        const RpcBackendConfig cfg = evaluate_vulkan_physical_device(probe, 4, false);
        assert(!cfg.gpu_backend_enabled);
        assert(cfg.mode == RpcBackendMode::ARMV8_0_NEON_CPU);
    }

    // 4. Compliant Vulkan 1.3 GPU with VK_KHR_16bit_storage and maxPushConstantsSize >= 256 -> Vulkan enabled
    {
        VulkanPhysicalDeviceProbe probe;
        probe.vulkan_library_loaded = true;
        probe.physical_device_found = true;
        probe.device_name = "Adreno (TM) 740";
        probe.api_version = VK_MAKE_API_VERSION(0, 1, 3, 0);
        probe.device_type = VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
        probe.max_push_constants_size = 256;
        probe.max_compute_work_group_invocations = 512;
        probe.has_compute_queue = true;
        probe.has_khr_16bit_storage_ext = true;
        probe.storage_buffer_16bit_access = true;

        const RpcBackendConfig cfg = evaluate_vulkan_physical_device(probe, 6, false);
        assert(cfg.gpu_backend_enabled);
        assert(cfg.mode == RpcBackendMode::VULKAN_GPU_FP16);
        assert(cfg.device_arg == "Vulkan0");

        apply_rpc_backend_environment(cfg);
        assert(std::getenv("GGML_VK_VISIBLE_DEVICES") == nullptr);
        assert(std::getenv("SILICA_NEON_THREADS") == nullptr);
        assert(std::getenv("SILICA_ZERO_FALLBACK_CPU") == nullptr);
        assert(std::string(std::getenv("SILICA_GPU_BACKEND_DISABLED")) == "0");

        const std::string report = format_probe_report(cfg);
        assert(report.find("VK_KHR_16bit_storage=1") != std::string::npos);
        assert(report.find("16bit_storage: 1") != std::string::npos);
        assert(report.find("maxPushConstantsSize=256") != std::string::npos);
        assert(report.find("rpc_backend_gpu_enabled=1") != std::string::npos);
        assert(report.find("rpc_backend_zero_fallback_cpu=0") != std::string::npos);
        assert(report.find("rpc_backend_vk_12_plus=1") != std::string::npos);
        assert(report.find("rpc_backend_vk_16bit_storage=1") != std::string::npos);
    }

    // 5. Multi-device selection prefers hardware GPU with VK_KHR_16bit_storage over uncapable/software GPUs
    {
        VulkanPhysicalDeviceProbe sw;
        sw.vulkan_library_loaded = true;
        sw.physical_device_found = true;
        sw.device_name = "SwiftShader";
        sw.api_version = VK_MAKE_API_VERSION(0, 1, 3, 0);
        sw.device_type = VK_PHYSICAL_DEVICE_TYPE_CPU;
        sw.max_push_constants_size = 256;
        sw.has_khr_16bit_storage_ext = true;
        sw.storage_buffer_16bit_access = true;

        VulkanPhysicalDeviceProbe hw_no16;
        hw_no16.vulkan_library_loaded = true;
        hw_no16.physical_device_found = true;
        hw_no16.device_name = "Adreno (TM) 619";
        hw_no16.api_version = VK_MAKE_API_VERSION(0, 1, 2, 0);
        hw_no16.device_type = VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
        hw_no16.max_push_constants_size = 256;
        hw_no16.max_compute_work_group_invocations = 512;
        hw_no16.has_compute_queue = true;
        hw_no16.has_khr_16bit_storage_ext = false;
        hw_no16.storage_buffer_16bit_access = false;

        VulkanPhysicalDeviceProbe hw_ok = hw_no16;
        hw_ok.device_name = "Adreno (TM) 740";
        hw_ok.api_version = VK_MAKE_API_VERSION(0, 1, 3, 0);
        hw_ok.has_khr_16bit_storage_ext = true;
        hw_ok.storage_buffer_16bit_access = true;

        const auto selected_best = select_best_physical_device_candidate({sw, hw_no16, hw_ok});
        assert(selected_best.device_name == "Adreno (TM) 740");

        const auto selected_fallback = select_best_physical_device_candidate({sw, hw_no16});
        assert(selected_fallback.device_name == "Adreno (TM) 619");
        const RpcBackendConfig fallback_cfg = evaluate_vulkan_physical_device(selected_fallback, 0, false);
        assert(!fallback_cfg.gpu_backend_enabled);
        assert(fallback_cfg.mode == RpcBackendMode::ARMV8_0_NEON_CPU);
        assert(fallback_cfg.cpu_threads >= 1);
    }

    // 6. Unreliable Vulkan driver (extension enumeration error / fault) forces ARMv8.0-A NEON CPU fallback
    {
        VulkanPhysicalDeviceProbe unreliable;
        unreliable.vulkan_library_loaded = true;
        unreliable.physical_device_found = true;
        unreliable.device_name = "Adreno (TM) 740";
        unreliable.api_version = VK_MAKE_API_VERSION(0, 1, 3, 0);
        unreliable.device_type = VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
        unreliable.max_push_constants_size = 256;
        unreliable.max_compute_work_group_invocations = 512;
        unreliable.has_compute_queue = true;
        unreliable.has_khr_16bit_storage_ext = true;
        unreliable.storage_buffer_16bit_access = true;
        unreliable.driver_unreliable = true;
        unreliable.probe_error = "vkEnumerateDeviceExtensionProperties failed";

        const RpcBackendConfig cfg = evaluate_vulkan_physical_device(unreliable, 6, false);
        assert(!cfg.gpu_backend_enabled);
        assert(cfg.mode == RpcBackendMode::ARMV8_0_NEON_CPU);
        assert(cfg.device_arg == "CPU");
        assert(cfg.reason.find("unreliable") != std::string::npos);

        const std::string report = format_probe_report(cfg);
        assert(report.find("rpc_backend_gpu_enabled=0") != std::string::npos);
        assert(report.find("rpc_backend_driver_reliable=0") != std::string::npos);
    }

    // 7. Device supports VK_KHR_16bit_storage but lacks VK_QUEUE_COMPUTE_BIT -> GPU disabled, ARMv8.0-A NEON CPU
    {
        VulkanPhysicalDeviceProbe no_compute;
        no_compute.vulkan_library_loaded = true;
        no_compute.physical_device_found = true;
        no_compute.device_name = "Display-Only GPU";
        no_compute.api_version = VK_MAKE_API_VERSION(0, 1, 3, 0);
        no_compute.device_type = VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
        no_compute.max_push_constants_size = 256;
        no_compute.max_compute_work_group_invocations = 512;
        no_compute.has_compute_queue = false;
        no_compute.has_khr_16bit_storage_ext = true;
        no_compute.storage_buffer_16bit_access = true;

        const RpcBackendConfig cfg = evaluate_vulkan_physical_device(no_compute, 6, false);
        assert(!cfg.gpu_backend_enabled);
        assert(cfg.mode == RpcBackendMode::ARMV8_0_NEON_CPU);
        assert(cfg.device_arg == "CPU");
        assert(cfg.reason.find("compute support") != std::string::npos);

        const std::string report = format_probe_report(cfg);
        assert(report.find("rpc_backend_vk_compute=0") != std::string::npos);
        assert(report.find("compute_queue=0") != std::string::npos);
    }

    // 8. Vulkan 1.1 (< 1.2) device with VK_KHR_16bit_storage is rejected -> GPU disabled, ARMv8.0-A NEON CPU
    {
        VulkanPhysicalDeviceProbe vk11;
        vk11.vulkan_library_loaded = true;
        vk11.physical_device_found = true;
        vk11.device_name = "Adreno (TM) 640";
        vk11.api_version = VK_MAKE_API_VERSION(0, 1, 1, 0);
        vk11.device_type = VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
        vk11.max_push_constants_size = 256;
        vk11.max_compute_work_group_invocations = 512;
        vk11.has_compute_queue = true;
        vk11.has_khr_16bit_storage_ext = true;
        vk11.storage_buffer_16bit_access = true;

        const RpcBackendConfig cfg = evaluate_vulkan_physical_device(vk11, 6, false);
        assert(!cfg.gpu_backend_enabled);
        assert(cfg.mode == RpcBackendMode::ARMV8_0_NEON_CPU);
        assert(cfg.device_arg == "CPU");
        assert(cfg.cpu_isa_target == "ARMv8.0-A NEON");
        assert(cfg.reason.find("< 1.2") != std::string::npos);

        const std::string report = format_probe_report(cfg);
        assert(report.find("rpc_backend_vk_12_plus=0") != std::string::npos);
    }

    // 9. Driver instability during vkCreateDevice forces immediate NEON CPU fallback with zero GPU layers
    {
        VulkanPhysicalDeviceProbe create_fail;
        create_fail.vulkan_library_loaded = true;
        create_fail.physical_device_found = true;
        create_fail.device_name = "Mali-G710 MC10";
        create_fail.api_version = VK_MAKE_API_VERSION(0, 1, 3, 0);
        create_fail.device_type = VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
        create_fail.max_push_constants_size = 256;
        create_fail.max_compute_work_group_invocations = 512;
        create_fail.has_compute_queue = true;
        create_fail.has_khr_16bit_storage_ext = true;
        create_fail.storage_buffer_16bit_access = true;
        create_fail.device_create_verified = false;
        create_fail.driver_unreliable = true;
        create_fail.probe_error = "vkCreateDevice failed (-4)";

        const RpcBackendConfig cfg = evaluate_vulkan_physical_device(create_fail, 0, false);
        assert(!cfg.gpu_backend_enabled);
        assert(cfg.mode == RpcBackendMode::ARMV8_0_NEON_CPU);
        assert(cfg.n_gpu_layers == 0);
        assert(cfg.device_arg == "CPU");
        assert(cfg.cpu_threads >= 1);

        const std::string report = format_probe_report(cfg);
        assert(report.find("rpc_backend_vk_device_create=0") != std::string::npos);
        assert(report.find("rpc_backend_driver_reliable=0") != std::string::npos);
    }

    std::cout << "All rpc_server.cpp Vulkan PhysicalDeviceProperties & ARMv8.0-A NEON tests passed!" << std::endl;
    return 0;
}
