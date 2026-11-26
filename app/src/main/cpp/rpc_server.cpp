// =============================================================================
// Silica Cluster Android Worker — Native RPC Server & Vulkan Capability Probe
//
// Probes Vulkan VkPhysicalDeviceProperties (deviceName, apiVersion >= 1.2,
// deviceType, limits.maxPushConstantsSize) and VK_KHR_16bit_storage (extension +
// VkPhysicalDevice16BitStorageFeatures::storageBuffer16BitAccess) via libvulkan.so.
//
// If Vulkan 1.2+ or VK_KHR_16bit_storage is missing, driver is unstable, or
// maxPushConstantsSize < 256 bytes, explicitly disables the GPU backend and
// initializes the optimized ARMv8.0-A NEON multi-threaded CPU execution path.
// =============================================================================

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <iostream>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

#if defined(__linux__) || defined(__ANDROID__)
#include <dlfcn.h>
#include <unistd.h>
#endif

#if defined(__ANDROID__) && defined(__has_include)
#if __has_include(<jni.h>)
#include <jni.h>
#define SILICA_HAS_JNI 1
#endif
#if __has_include(<android/log.h>)
#include <android/log.h>
#define SILICA_HAS_ANDROID_LOG 1
#endif
#endif

#if defined(__ARM_NEON) || defined(__aarch64__)
#include <arm_neon.h>
#define SILICA_COMPILED_WITH_ARM_NEON 1
#endif

// -----------------------------------------------------------------------------
// Minimal standard Vulkan 1.1 ABI definitions (so rpc_server.cpp compiles cleanly
// with or without Vulkan SDK headers installed, and binds dynamically at runtime
// to Android's /system/lib64/libvulkan.so via dlopen/dlsym).
// -----------------------------------------------------------------------------
#if defined(__has_include) && __has_include(<vulkan/vulkan.h>)
#include <vulkan/vulkan.h>
#else
using VkFlags = uint32_t;
using VkBool32 = uint32_t;
using VkDeviceSize = uint64_t;
using VkResult = int32_t;
using VkInstance = void*;
using VkPhysicalDevice = void*;
using VkDevice = void*;

constexpr VkResult VK_SUCCESS = 0;
constexpr VkResult VK_ERROR_INITIALIZATION_FAILED = -3;
constexpr VkResult VK_ERROR_DEVICE_LOST = -4;
constexpr VkResult VK_ERROR_EXTENSION_NOT_PRESENT = -7;
constexpr VkResult VK_ERROR_FEATURE_NOT_PRESENT = -8;
constexpr VkBool32 VK_FALSE = 0;
constexpr VkBool32 VK_TRUE = 1;
constexpr uint32_t VK_MAX_PHYSICAL_DEVICE_NAME_SIZE = 256;
constexpr uint32_t VK_UUID_SIZE = 16;
constexpr uint32_t VK_MAX_EXTENSION_NAME_SIZE = 256;

constexpr uint32_t VK_STRUCTURE_TYPE_APPLICATION_INFO = 0;
constexpr uint32_t VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO = 1;
constexpr uint32_t VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO = 2;
constexpr uint32_t VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO = 3;
constexpr uint32_t VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 = 1000059000;
constexpr uint32_t VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES = 1000083000;

constexpr uint32_t VK_PHYSICAL_DEVICE_TYPE_OTHER = 0;
constexpr uint32_t VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU = 1;
constexpr uint32_t VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU = 2;
constexpr uint32_t VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU = 3;
constexpr uint32_t VK_PHYSICAL_DEVICE_TYPE_CPU = 4;
constexpr uint32_t VK_QUEUE_COMPUTE_BIT = 0x00000002U;

#ifndef VK_MAKE_API_VERSION
#define VK_MAKE_API_VERSION(variant, major, minor, patch) \
    ((((uint32_t)(variant)) << 29U) | (((uint32_t)(major)) << 22U) | (((uint32_t)(minor)) << 12U) | ((uint32_t)(patch)))
#endif
#ifndef VK_API_VERSION_MAJOR
#define VK_API_VERSION_MAJOR(version) (((uint32_t)(version) >> 22U) & 0x7FU)
#endif
#ifndef VK_API_VERSION_MINOR
#define VK_API_VERSION_MINOR(version) (((uint32_t)(version) >> 12U) & 0x3FFU)
#endif

struct VkApplicationInfo {
    uint32_t sType;
    const void* pNext;
    const char* pApplicationName;
    uint32_t applicationVersion;
    const char* pEngineName;
    uint32_t engineVersion;
    uint32_t apiVersion;
};

struct VkInstanceCreateInfo {
    uint32_t sType;
    const void* pNext;
    VkFlags flags;
    const VkApplicationInfo* pApplicationInfo;
    uint32_t enabledLayerCount;
    const char* const* ppEnabledLayerNames;
    uint32_t enabledExtensionCount;
    const char* const* ppEnabledExtensionNames;
};

struct VkPhysicalDeviceLimits {
    uint32_t maxImageDimension1D;
    uint32_t maxImageDimension2D;
    uint32_t maxImageDimension3D;
    uint32_t maxImageDimensionCube;
    uint32_t maxImageArrayLayers;
    uint32_t maxTexelBufferElements;
    uint32_t maxUniformBufferRange;
    uint32_t maxStorageBufferRange;
    uint32_t maxPushConstantsSize;
    uint32_t maxMemoryAllocationCount;
    uint32_t maxSamplerAllocationCount;
    VkDeviceSize bufferImageGranularity;
    VkDeviceSize sparseAddressSpaceSize;
    uint32_t maxBoundDescriptorSets;
    uint32_t maxPerStageDescriptorSamplers;
    uint32_t maxPerStageDescriptorUniformBuffers;
    uint32_t maxPerStageDescriptorStorageBuffers;
    uint32_t maxPerStageDescriptorSampledImages;
    uint32_t maxPerStageDescriptorStorageImages;
    uint32_t maxPerStageDescriptorInputAttachments;
    uint32_t maxPerStageResources;
    uint32_t maxDescriptorSetSamplers;
    uint32_t maxDescriptorSetUniformBuffers;
    uint32_t maxDescriptorSetUniformBuffersDynamic;
    uint32_t maxDescriptorSetStorageBuffers;
    uint32_t maxDescriptorSetStorageBuffersDynamic;
    uint32_t maxDescriptorSetSampledImages;
    uint32_t maxDescriptorSetStorageImages;
    uint32_t maxDescriptorSetInputAttachments;
    uint32_t maxVertexInputAttributes;
    uint32_t maxVertexInputBindings;
    uint32_t maxVertexInputAttributeOffset;
    uint32_t maxVertexInputBindingStride;
    uint32_t maxVertexOutputComponents;
    uint32_t maxTessellationGenerationLevel;
    uint32_t maxTessellationPatchSize;
    uint32_t maxTessellationControlPerVertexInputComponents;
    uint32_t maxTessellationControlPerVertexOutputComponents;
    uint32_t maxTessellationControlPerPatchOutputComponents;
    uint32_t maxTessellationControlTotalOutputComponents;
    uint32_t maxTessellationEvaluationInputComponents;
    uint32_t maxTessellationEvaluationOutputComponents;
    uint32_t maxGeometryShaderInvocations;
    uint32_t maxGeometryInputComponents;
    uint32_t maxGeometryOutputComponents;
    uint32_t maxGeometryOutputVertices;
    uint32_t maxGeometryTotalOutputComponents;
    uint32_t maxFragmentInputComponents;
    uint32_t maxFragmentOutputAttachments;
    uint32_t maxFragmentDualSrcAttachments;
    uint32_t maxFragmentCombinedOutputResources;
    uint32_t maxComputeSharedMemorySize;
    uint32_t maxComputeWorkGroupCount[3];
    uint32_t maxComputeWorkGroupInvocations;
    uint32_t maxComputeWorkGroupSize[3];
    uint32_t subPixelPrecisionBits;
    uint32_t subTexelPrecisionBits;
    uint32_t mipmapPrecisionBits;
    uint32_t maxDrawIndexedIndexValue;
    uint32_t maxDrawIndirectCount;
    float maxSamplerLodBias;
    float maxSamplerAnisotropy;
    uint32_t maxViewports;
    uint32_t maxViewportDimensions[2];
    float viewportBoundsRange[2];
    uint32_t viewportSubPixelBits;
    size_t minMemoryMapAlignment;
    VkDeviceSize minTexelBufferOffsetAlignment;
    VkDeviceSize minUniformBufferOffsetAlignment;
    VkDeviceSize minStorageBufferOffsetAlignment;
    int32_t minTexelOffset;
    uint32_t maxTexelOffset;
    int32_t minTexelGatherOffset;
    uint32_t maxTexelGatherOffset;
    float minInterpolationOffset;
    float maxInterpolationOffset;
    uint32_t subPixelInterpolationOffsetBits;
    uint32_t maxFramebufferWidth;
    uint32_t maxFramebufferHeight;
    uint32_t maxFramebufferLayers;
    VkFlags framebufferColorSampleCounts;
    VkFlags framebufferDepthSampleCounts;
    VkFlags framebufferStencilSampleCounts;
    VkFlags framebufferNoAttachmentsSampleCounts;
    uint32_t maxColorAttachments;
    VkFlags sampledImageColorSampleCounts;
    VkFlags sampledImageIntegerSampleCounts;
    VkFlags sampledImageDepthSampleCounts;
    VkFlags sampledImageStencilSampleCounts;
    VkFlags storageImageSampleCounts;
    uint32_t maxSampleMaskWords;
    VkBool32 timestampComputeAndGraphics;
    float timestampPeriod;
    uint32_t maxClipDistances;
    uint32_t maxCullDistances;
    uint32_t maxCombinedClipAndCullDistances;
    uint32_t discreteQueuePriorities;
    float pointSizeRange[2];
    float lineWidthRange[2];
    float pointSizeGranularity;
    float lineWidthGranularity;
    VkBool32 strictLines;
    VkBool32 standardSampleLocations;
    VkDeviceSize optimalBufferCopyOffsetAlignment;
    VkDeviceSize optimalBufferCopyRowPitchAlignment;
    VkDeviceSize nonCoherentAtomSize;
};

struct VkPhysicalDeviceSparseProperties {
    VkBool32 residencyStandard2DBlockShape;
    VkBool32 residencyStandard2DMultisampleBlockShape;
    VkBool32 residencyStandard3DBlockShape;
    VkBool32 residencyAlignedMipSize;
    VkBool32 residencyNonResidentStrict;
};

struct VkPhysicalDeviceProperties {
    uint32_t apiVersion;
    uint32_t driverVersion;
    uint32_t vendorID;
    uint32_t deviceID;
    uint32_t deviceType;
    char deviceName[VK_MAX_PHYSICAL_DEVICE_NAME_SIZE];
    uint8_t pipelineCacheUUID[VK_UUID_SIZE];
    VkPhysicalDeviceLimits limits;
    VkPhysicalDeviceSparseProperties sparseProperties;
};

struct VkExtensionProperties {
    char extensionName[VK_MAX_EXTENSION_NAME_SIZE];
    uint32_t specVersion;
};

struct VkPhysicalDeviceFeatures {
    VkBool32 features[55];
};

struct VkPhysicalDeviceFeatures2 {
    uint32_t sType;
    void* pNext;
    VkPhysicalDeviceFeatures features;
};

struct VkPhysicalDevice16BitStorageFeatures {
    uint32_t sType;
    void* pNext;
    VkBool32 storageBuffer16BitAccess;
    VkBool32 uniformAndStorageBuffer16BitAccess;
    VkBool32 storagePushConstant16;
    VkBool32 storageInputOutput16;
};

struct VkExtent3D {
    uint32_t width;
    uint32_t height;
    uint32_t depth;
};

struct VkQueueFamilyProperties {
    VkFlags queueFlags;
    uint32_t queueCount;
    uint32_t timestampValidBits;
    VkExtent3D minImageTransferGranularity;
};

struct VkDeviceQueueCreateInfo {
    uint32_t sType;
    const void* pNext;
    VkFlags flags;
    uint32_t queueFamilyIndex;
    uint32_t queueCount;
    const float* pQueuePriorities;
};

struct VkDeviceCreateInfo {
    uint32_t sType;
    const void* pNext;
    VkFlags flags;
    uint32_t queueCreateInfoCount;
    const VkDeviceQueueCreateInfo* pQueueCreateInfos;
    uint32_t enabledLayerCount;
    const char* const* ppEnabledLayerNames;
    uint32_t enabledExtensionCount;
    const char* const* ppEnabledExtensionNames;
    const VkPhysicalDeviceFeatures* pEnabledFeatures;
};
#endif

namespace silica {

// Minimum Vulkan API version (1.2.0) and push-constant budget (256 bytes) required by
// ggml-vulkan compute dispatch shaders on Android mobile GPUs.
constexpr uint32_t kMinVulkanApiVersion = VK_MAKE_API_VERSION(0, 1, 2, 0);
constexpr uint32_t kMinRequiredPushConstantsSize = 256U;
constexpr uint32_t kMinComputeWorkGroupInvocations = 128U;
constexpr const char* kVkKhr16BitStorageExt = "VK_KHR_16bit_storage";

enum class RpcBackendMode {
    VULKAN_GPU_FP16,
    ARMV8_0_NEON_CPU
};

struct VulkanPhysicalDeviceProbe {
    bool vulkan_library_loaded = false;
    bool physical_device_found = false;
    std::string device_name = "None";
    uint32_t api_version = 0;
    uint32_t driver_version = 0;
    uint32_t vendor_id = 0;
    uint32_t device_id = 0;
    uint32_t device_type = VK_PHYSICAL_DEVICE_TYPE_OTHER;
    uint32_t max_push_constants_size = 0;
    uint32_t max_compute_work_group_invocations = 0;
    uint32_t compute_queue_family_index = 0;
    bool has_compute_queue = false;
    bool has_khr_16bit_storage_ext = false;
    bool storage_buffer_16bit_access = false;
    bool device_create_verified = false;
    bool driver_unreliable = false;
    std::string probe_error;
};

struct RpcBackendConfig {
    RpcBackendMode mode = RpcBackendMode::ARMV8_0_NEON_CPU;
    bool gpu_backend_enabled = false;
    int32_t n_gpu_layers = 0;
    int32_t cpu_threads = 4;
    std::string cpu_isa_target = "ARMv8.0-A NEON";
    std::string device_arg = "CPU";
    std::string reason;
    VulkanPhysicalDeviceProbe vulkan_probe;
};

inline std::string to_lower_ascii(std::string s) {
    std::transform(s.begin(), s.end(), s.begin(), [](unsigned char c) {
        return static_cast<char>(std::tolower(c));
    });
    return s;
}

inline std::string format_vk_version(uint32_t version) {
    if (version == 0) {
        return "None";
    }
    return std::to_string(VK_API_VERSION_MAJOR(version)) + "." +
           std::to_string(VK_API_VERSION_MINOR(version));
}

inline bool is_software_vulkan_device(const VulkanPhysicalDeviceProbe& probe) {
    if (probe.device_type == VK_PHYSICAL_DEVICE_TYPE_CPU) {
        return true;
    }
    const std::string lower = to_lower_ascii(probe.device_name);
    return lower.find("swiftshader") != std::string::npos ||
           lower.find("llvmpipe") != std::string::npos ||
           lower.find("lavapipe") != std::string::npos ||
           lower.find("ranchu") != std::string::npos ||
           lower.find("goldfish") != std::string::npos ||
           lower.find("pastel") != std::string::npos;
}

inline int score_physical_device_candidate(const VulkanPhysicalDeviceProbe& probe) {
    if (!probe.vulkan_library_loaded || !probe.physical_device_found || probe.driver_unreliable) {
        return -1;
    }
    if (is_software_vulkan_device(probe)) {
        return 0;
    }
    if (probe.api_version < kMinVulkanApiVersion) {
        return 1;
    }
    if (!probe.has_compute_queue || probe.max_compute_work_group_invocations < kMinComputeWorkGroupInvocations) {
        return 1;
    }
    if (!probe.has_khr_16bit_storage_ext || !probe.storage_buffer_16bit_access) {
        return 2;
    }
    if (probe.max_push_constants_size < kMinRequiredPushConstantsSize) {
        return 3;
    }
    return 4;
}

inline VulkanPhysicalDeviceProbe select_best_physical_device_candidate(
    const std::vector<VulkanPhysicalDeviceProbe>& candidates
) {
    VulkanPhysicalDeviceProbe best;
    int best_score = -2;
    for (const auto& cand : candidates) {
        const int score = score_physical_device_candidate(cand);
        if (score > best_score) {
            best = cand;
            best_score = score;
        }
    }
    return best;
}

/**
 * Pure, deterministic evaluation of VkPhysicalDeviceProperties & VK_KHR_16bit_storage.
 * Explicitly disables the GPU backend and selects the optimized ARMv8.0-A NEON
 * multi-threaded CPU execution path whenever:
 *  - Vulkan or a hardware physical device is unavailable
 *  - The physical device is a software/CPU Vulkan renderer
 *  - Vulkan apiVersion < 1.2
 *  - VK_KHR_16bit_storage extension or storageBuffer16BitAccess is missing
 *  - limits.maxPushConstantsSize < 256 bytes
 */
inline RpcBackendConfig evaluate_vulkan_physical_device(
    const VulkanPhysicalDeviceProbe& probe,
    int32_t requested_threads,
    bool force_cpu_override = false
) {
    RpcBackendConfig cfg;
    cfg.vulkan_probe = probe;

    const int32_t hw_cores = std::max<int32_t>(1, static_cast<int32_t>(std::thread::hardware_concurrency()));
    const int32_t auto_neon_threads = (hw_cores >= 6)
        ? std::max<int32_t>(4, hw_cores - 2)
        : hw_cores;
    const int32_t default_threads = std::max<int32_t>(
        1,
        std::min<int32_t>(hw_cores, requested_threads > 0 ? requested_threads : auto_neon_threads)
    );
    cfg.cpu_threads = default_threads;

    auto select_neon_fallback = [&](const std::string& reason) -> RpcBackendConfig {
        cfg.mode = RpcBackendMode::ARMV8_0_NEON_CPU;
        cfg.gpu_backend_enabled = false;
        cfg.n_gpu_layers = 0;
        cfg.cpu_isa_target = "ARMv8.0-A NEON";
        cfg.device_arg = "CPU";
        cfg.reason = reason;
        return cfg;
    };

    if (force_cpu_override) {
        return select_neon_fallback("Explicit CPU override requested -> ARMv8.0-A NEON multi-threaded CPU");
    }

    if (!probe.vulkan_library_loaded) {
        return select_neon_fallback(
            "libvulkan.so unavailable (" +
            (probe.probe_error.empty() ? "missing" : probe.probe_error) +
            ") -> GPU backend disabled, using ARMv8.0-A NEON multi-threaded CPU"
        );
    }

    if (probe.driver_unreliable) {
        return select_neon_fallback(
            "Vulkan driver reported unreliable state (" +
            (probe.probe_error.empty() ? probe.device_name : probe.probe_error) +
            ") -> GPU backend disabled, using ARMv8.0-A NEON multi-threaded CPU"
        );
    }

    if (!probe.physical_device_found) {
        return select_neon_fallback(
            "No Vulkan physical device enumerated -> GPU backend disabled, using ARMv8.0-A NEON multi-threaded CPU"
        );
    }

    if (is_software_vulkan_device(probe)) {
        return select_neon_fallback(
            "Software Vulkan physical device (" + probe.device_name +
            ") -> GPU backend disabled, using ARMv8.0-A NEON multi-threaded CPU"
        );
    }

    if (probe.api_version < kMinVulkanApiVersion) {
        return select_neon_fallback(
            "Vulkan apiVersion " + format_vk_version(probe.api_version) +
            " < 1.2 on " + probe.device_name +
            " -> GPU backend disabled, using ARMv8.0-A NEON multi-threaded CPU"
        );
    }

    if (!probe.has_compute_queue || probe.max_compute_work_group_invocations < kMinComputeWorkGroupInvocations) {
        return select_neon_fallback(
            "PhysicalDevice (" + probe.device_name +
            ") lacks Vulkan compute support (computeQueue=" +
            std::to_string(probe.has_compute_queue ? 1 : 0) +
            ", maxComputeWorkGroupInvocations=" +
            std::to_string(probe.max_compute_work_group_invocations) +
            ") -> GPU backend disabled, using ARMv8.0-A NEON multi-threaded CPU"
        );
    }

    if (!probe.has_khr_16bit_storage_ext || !probe.storage_buffer_16bit_access) {
        return select_neon_fallback(
            "PhysicalDevice (" + probe.device_name +
            ") lacks VK_KHR_16bit_storage (ext=" +
            std::to_string(probe.has_khr_16bit_storage_ext ? 1 : 0) +
            ", storageBuffer16BitAccess=" +
            std::to_string(probe.storage_buffer_16bit_access ? 1 : 0) +
            ") -> GPU backend disabled, using ARMv8.0-A NEON multi-threaded CPU"
        );
    }

    if (probe.max_push_constants_size < kMinRequiredPushConstantsSize) {
        return select_neon_fallback(
            "PhysicalDevice (" + probe.device_name +
            ") reports low maxPushConstantsSize=" +
            std::to_string(probe.max_push_constants_size) +
            " bytes (< " + std::to_string(kMinRequiredPushConstantsSize) +
            " required) -> GPU backend disabled, using ARMv8.0-A NEON multi-threaded CPU"
        );
    }

    cfg.mode = RpcBackendMode::VULKAN_GPU_FP16;
    cfg.gpu_backend_enabled = true;
    cfg.n_gpu_layers = -1; // Delegate layer budget to Master planner
    cfg.cpu_isa_target = "ARMv8.0-A NEON";
    cfg.device_arg = "Vulkan0";
    cfg.reason = "Vulkan " + format_vk_version(probe.api_version) +
                 " verified (" + probe.device_name +
                 ", VK_KHR_16bit_storage=1, maxPushConstantsSize=" +
                 std::to_string(probe.max_push_constants_size) + ")";
    return cfg;
}

/**
 * Dynamically probes libvulkan.so on Android/Linux for VkPhysicalDeviceProperties
 * (including limits.maxPushConstantsSize) and VK_KHR_16bit_storage +
 * VkPhysicalDevice16BitStorageFeatures::storageBuffer16BitAccess.
 */
inline VulkanPhysicalDeviceProbe probe_vulkan_physical_device_properties() {
    VulkanPhysicalDeviceProbe out;

#if defined(__linux__) || defined(__ANDROID__)
    void* vk_lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!vk_lib) {
        vk_lib = dlopen("libvulkan.so.1", RTLD_NOW | RTLD_LOCAL);
    }
    if (!vk_lib) {
        out.probe_error = "dlopen(libvulkan.so) failed";
        return out;
    }
    out.vulkan_library_loaded = true;

    using PFN_vkCreateInstance = VkResult (*)(const VkInstanceCreateInfo*, const void*, VkInstance*);
    using PFN_vkDestroyInstance = void (*)(VkInstance, const void*);
    using PFN_vkEnumeratePhysicalDevices = VkResult (*)(VkInstance, uint32_t*, VkPhysicalDevice*);
    using PFN_vkGetPhysicalDeviceProperties = void (*)(VkPhysicalDevice, VkPhysicalDeviceProperties*);
    using PFN_vkGetPhysicalDeviceQueueFamilyProperties = void (*)(VkPhysicalDevice, uint32_t*, VkQueueFamilyProperties*);
    using PFN_vkEnumerateDeviceExtensionProperties = VkResult (*)(VkPhysicalDevice, const char*, uint32_t*, VkExtensionProperties*);
    using PFN_vkGetInstanceProcAddr = void* (*)(VkInstance, const char*);
    using PFN_vkGetPhysicalDeviceFeatures2 = void (*)(VkPhysicalDevice, VkPhysicalDeviceFeatures2*);
    using PFN_vkCreateDevice = VkResult (*)(VkPhysicalDevice, const VkDeviceCreateInfo*, const void*, VkDevice*);
    using PFN_vkDestroyDevice = void (*)(VkDevice, const void*);

    auto fn_create_instance = reinterpret_cast<PFN_vkCreateInstance>(dlsym(vk_lib, "vkCreateInstance"));
    auto fn_destroy_instance = reinterpret_cast<PFN_vkDestroyInstance>(dlsym(vk_lib, "vkDestroyInstance"));
    auto fn_enum_devices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(dlsym(vk_lib, "vkEnumeratePhysicalDevices"));
    auto fn_get_props = reinterpret_cast<PFN_vkGetPhysicalDeviceProperties>(dlsym(vk_lib, "vkGetPhysicalDeviceProperties"));
    auto fn_get_queue_families = reinterpret_cast<PFN_vkGetPhysicalDeviceQueueFamilyProperties>(dlsym(vk_lib, "vkGetPhysicalDeviceQueueFamilyProperties"));
    auto fn_enum_exts = reinterpret_cast<PFN_vkEnumerateDeviceExtensionProperties>(dlsym(vk_lib, "vkEnumerateDeviceExtensionProperties"));
    auto fn_get_inst_proc = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(vk_lib, "vkGetInstanceProcAddr"));
    auto fn_create_device = reinterpret_cast<PFN_vkCreateDevice>(dlsym(vk_lib, "vkCreateDevice"));
    auto fn_destroy_device = reinterpret_cast<PFN_vkDestroyDevice>(dlsym(vk_lib, "vkDestroyDevice"));

    if (!fn_create_instance || !fn_destroy_instance || !fn_enum_devices || !fn_get_props || !fn_enum_exts) {
        out.driver_unreliable = true;
        out.probe_error = "Missing core Vulkan loader symbols";
        dlclose(vk_lib);
        return out;
    }

    VkApplicationInfo app_info{};
    app_info.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    app_info.pApplicationName = "SilicaRpcWorker";
    app_info.applicationVersion = 1;
    app_info.pEngineName = "SilicaGGML";
    app_info.engineVersion = 1;
    app_info.apiVersion = kMinVulkanApiVersion;

    VkInstanceCreateInfo create_info{};
    create_info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    create_info.pApplicationInfo = &app_info;

    VkInstance instance = nullptr;
    if (fn_create_instance(&create_info, nullptr, &instance) != VK_SUCCESS || !instance) {
        // Retry with Vulkan 1.0 instance in case loader rejects 1.2; apiVersion check will catch < 1.2
        app_info.apiVersion = VK_MAKE_API_VERSION(0, 1, 0, 0);
        if (fn_create_instance(&create_info, nullptr, &instance) != VK_SUCCESS || !instance) {
            out.driver_unreliable = true;
            out.probe_error = "vkCreateInstance failed";
            dlclose(vk_lib);
            return out;
        }
    }

    auto fn_get_features2 = fn_get_inst_proc
        ? reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(fn_get_inst_proc(instance, "vkGetPhysicalDeviceFeatures2"))
        : nullptr;
    if (!fn_get_features2 && fn_get_inst_proc) {
        fn_get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            fn_get_inst_proc(instance, "vkGetPhysicalDeviceFeatures2KHR"));
    }
    if (!fn_get_features2) {
        fn_get_features2 = reinterpret_cast<PFN_vkGetPhysicalDeviceFeatures2>(
            dlsym(vk_lib, "vkGetPhysicalDeviceFeatures2"));
    }

    uint32_t device_count = 0;
    const VkResult enum_res = fn_enum_devices(instance, &device_count, nullptr);
    if (enum_res == VK_SUCCESS && device_count > 0) {
        std::vector<VkPhysicalDevice> devices(device_count);
        if (fn_enum_devices(instance, &device_count, devices.data()) == VK_SUCCESS && device_count > 0) {
            // Collect all physical devices and select the highest-capability hardware GPU
            // (preferring non-software devices supporting Vulkan 1.2+ + VK_QUEUE_COMPUTE_BIT + VK_KHR_16bit_storage + push constants >= 256B)
            std::vector<VulkanPhysicalDeviceProbe> candidates;
            candidates.reserve(device_count);

            for (uint32_t i = 0; i < device_count; ++i) {
                VkPhysicalDeviceProperties props{};
                fn_get_props(devices[i], &props);

                VulkanPhysicalDeviceProbe cand;
                cand.vulkan_library_loaded = true;
                cand.physical_device_found = true;
                cand.device_name = props.deviceName[0] ? std::string(props.deviceName) : "Unknown Vulkan GPU";
                cand.api_version = props.apiVersion;
                cand.driver_version = props.driverVersion;
                cand.vendor_id = props.vendorID;
                cand.device_id = props.deviceID;
                cand.device_type = props.deviceType;
                cand.max_push_constants_size = props.limits.maxPushConstantsSize;
                cand.max_compute_work_group_invocations = props.limits.maxComputeWorkGroupInvocations;

                // Explicitly probe Vulkan compute queue family support (VK_QUEUE_COMPUTE_BIT)
                if (fn_get_queue_families) {
                    uint32_t qf_count = 0;
                    fn_get_queue_families(devices[i], &qf_count, nullptr);
                    if (qf_count > 0) {
                        std::vector<VkQueueFamilyProperties> qfs(qf_count);
                        fn_get_queue_families(devices[i], &qf_count, qfs.data());
                        for (uint32_t qf_idx = 0; qf_idx < qf_count; ++qf_idx) {
                            const auto& qf = qfs[qf_idx];
                            if ((qf.queueFlags & VK_QUEUE_COMPUTE_BIT) != 0 && qf.queueCount > 0) {
                                cand.has_compute_queue = true;
                                cand.compute_queue_family_index = qf_idx;
                                break;
                            }
                        }
                    }
                } else {
                    cand.driver_unreliable = true;
                    cand.probe_error = "vkGetPhysicalDeviceQueueFamilyProperties symbol missing";
                }

                // Enumerate device extensions to verify VK_KHR_16bit_storage
                uint32_t ext_count = 0;
                const VkResult ext_count_res = fn_enum_exts(devices[i], nullptr, &ext_count, nullptr);
                bool advertised_khr_16bit_ext = false;
                if (ext_count_res != VK_SUCCESS) {
                    cand.driver_unreliable = true;
                    cand.probe_error = "vkEnumerateDeviceExtensionProperties failed";
                } else if (ext_count > 0) {
                    std::vector<VkExtensionProperties> exts(ext_count);
                    if (fn_enum_exts(devices[i], nullptr, &ext_count, exts.data()) == VK_SUCCESS) {
                        for (const auto& ext : exts) {
                            if (std::strcmp(ext.extensionName, kVkKhr16BitStorageExt) == 0) {
                                advertised_khr_16bit_ext = true;
                                cand.has_khr_16bit_storage_ext = true;
                                break;
                            }
                        }
                    } else {
                        cand.driver_unreliable = true;
                        cand.probe_error = "vkEnumerateDeviceExtensionProperties read failed";
                    }
                }

                // Query VkPhysicalDevice16BitStorageFeatures::storageBuffer16BitAccess via vkGetPhysicalDeviceFeatures2
                if (fn_get_features2 && cand.api_version >= VK_MAKE_API_VERSION(0, 1, 1, 0)) {
                    VkPhysicalDevice16BitStorageFeatures storage16{};
                    storage16.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
                    storage16.pNext = nullptr;

                    VkPhysicalDeviceFeatures2 features2{};
                    features2.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2;
                    features2.pNext = &storage16;

                    fn_get_features2(devices[i], &features2);
                    cand.storage_buffer_16bit_access = (storage16.storageBuffer16BitAccess == VK_TRUE);
                    // On Vulkan 1.1+ core, storageBuffer16BitAccess implies VK_KHR_16bit_storage functionality
                    if (cand.storage_buffer_16bit_access) {
                        cand.has_khr_16bit_storage_ext = true;
                    }
                } else if (cand.api_version >= kMinVulkanApiVersion) {
                    cand.storage_buffer_16bit_access = false;
                    cand.driver_unreliable = true;
                    cand.probe_error = "vkGetPhysicalDeviceFeatures2 unavailable on Vulkan 1.2+ device";
                } else {
                    cand.storage_buffer_16bit_access = false;
                }

                // Verify live Vulkan compute VkDevice creation with VK_KHR_16bit_storage to catch
                // unstable vendor drivers before launching the inference RPC server.
                if (!cand.driver_unreliable &&
                    !is_software_vulkan_device(cand) &&
                    cand.api_version >= kMinVulkanApiVersion &&
                    cand.has_compute_queue &&
                    cand.has_khr_16bit_storage_ext &&
                    cand.storage_buffer_16bit_access &&
                    cand.max_push_constants_size >= kMinRequiredPushConstantsSize &&
                    fn_create_device &&
                    fn_destroy_device) {
                    const float queue_priority = 1.0f;
                    VkDeviceQueueCreateInfo queue_info{};
                    queue_info.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
                    queue_info.queueFamilyIndex = cand.compute_queue_family_index;
                    queue_info.queueCount = 1;
                    queue_info.pQueuePriorities = &queue_priority;

                    VkPhysicalDevice16BitStorageFeatures enabled_16bit{};
                    enabled_16bit.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_16BIT_STORAGE_FEATURES;
                    enabled_16bit.storageBuffer16BitAccess = VK_TRUE;

                    const char* ext_names[] = { kVkKhr16BitStorageExt };
                    VkDeviceCreateInfo dev_info{};
                    dev_info.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
                    dev_info.pNext = &enabled_16bit;
                    dev_info.queueCreateInfoCount = 1;
                    dev_info.pQueueCreateInfos = &queue_info;
                    if (advertised_khr_16bit_ext) {
                        dev_info.enabledExtensionCount = 1;
                        dev_info.ppEnabledExtensionNames = ext_names;
                    }

                    VkDevice probe_device = nullptr;
                    const VkResult dev_res = fn_create_device(devices[i], &dev_info, nullptr, &probe_device);
                    if (dev_res == VK_SUCCESS && probe_device) {
                        cand.device_create_verified = true;
                        fn_destroy_device(probe_device, nullptr);
                    } else if (dev_res == VK_ERROR_EXTENSION_NOT_PRESENT || dev_res == VK_ERROR_FEATURE_NOT_PRESENT) {
                        cand.storage_buffer_16bit_access = false;
                        cand.probe_error = "vkCreateDevice rejected 16bit_storage (" + std::to_string(dev_res) + ")";
                    } else {
                        cand.driver_unreliable = true;
                        cand.probe_error = "vkCreateDevice failed (" + std::to_string(dev_res) + ")";
                    }
                }

                candidates.push_back(cand);
            }

            if (!candidates.empty()) {
                out = select_best_physical_device_candidate(candidates);
            }
        } else {
            out.driver_unreliable = true;
            out.probe_error = "vkEnumeratePhysicalDevices failed during device list read";
        }
    } else if (enum_res != VK_SUCCESS) {
        out.driver_unreliable = true;
        out.probe_error = "vkEnumeratePhysicalDevices failed (" + std::to_string(enum_res) + ")";
    } else {
        out.probe_error = "vkEnumeratePhysicalDevices returned 0 devices";
    }

    fn_destroy_instance(instance, nullptr);
    dlclose(vk_lib);
#else
    out.probe_error = "Unsupported platform for libvulkan dlopen";
#endif
    return out;
}

/**
 * Applies the selected backend configuration to the current RPC server process environment.
 * When GPU backend is disabled (missing VK_KHR_16bit_storage or low maxPushConstantsSize),
 * hard-disables GGML Vulkan discovery and activates the optimized ARMv8.0-A NEON
 * multi-threaded CPU execution path.
 */
inline void apply_rpc_backend_environment(const RpcBackendConfig& cfg) {
#if defined(__linux__) || defined(__ANDROID__)
    const std::string threads_str = std::to_string(std::max<int32_t>(1, cfg.cpu_threads));
    setenv("OMP_NUM_THREADS", threads_str.c_str(), 1);
    setenv("OPENBLAS_NUM_THREADS", threads_str.c_str(), 1);
    setenv("GGML_NUM_THREADS", threads_str.c_str(), 1);
    setenv("OMP_WAIT_POLICY", "PASSIVE", 1);

    if (!cfg.gpu_backend_enabled || cfg.mode == RpcBackendMode::ARMV8_0_NEON_CPU) {
        // Explicitly disable the Vulkan GPU backend in rpc_server
        setenv("GGML_VK_VISIBLE_DEVICES", "", 1);
        setenv("GGML_VK_DISABLE_F16", "1", 1);
        setenv("GGML_VK_FORCE_MAX_ALLOCATION_SIZE", "0", 1);
        setenv("SILICA_GPU_BACKEND_DISABLED", "1", 1);

        // Hard zero-fallback switch to optimized ARMv8.0-A NEON multi-threaded CPU execution path
        setenv("SILICA_ZERO_FALLBACK_CPU", "1", 1);
        setenv("GGML_ARM_DISABLE_DOTPROD", "1", 1);
        setenv("GGML_ARM_DISABLE_I8MM", "1", 1);
        setenv("OMP_PROC_BIND", "close", 1);
        setenv("OMP_PLACES", "cores", 1);
        setenv("SILICA_CPU_EXEC_PATH", "ARMv8.0-A NEON", 1);
        setenv("SILICA_ARM_NEON_FASTPATH", "1", 1);
        setenv("SILICA_NEON_THREADS", threads_str.c_str(), 1);
    } else {
        unsetenv("GGML_VK_VISIBLE_DEVICES");
        unsetenv("GGML_VK_DISABLE_F16");
        unsetenv("GGML_VK_FORCE_MAX_ALLOCATION_SIZE");
        unsetenv("SILICA_NEON_THREADS");
        unsetenv("SILICA_ZERO_FALLBACK_CPU");
        unsetenv("GGML_ARM_DISABLE_DOTPROD");
        unsetenv("GGML_ARM_DISABLE_I8MM");
        setenv("SILICA_GPU_BACKEND_DISABLED", "0", 1);
        setenv("SILICA_CPU_EXEC_PATH", "Vulkan+NEON", 1);
    }
#else
    (void)cfg;
#endif
}

/**
 * Formats probe and backend initialization telemetry as a deterministic key=value block
 * consumed by Android RpcService.kt during startup probing.
 */
inline std::string format_probe_report(const RpcBackendConfig& cfg) {
    std::ostringstream oss;
    const bool vk_12_plus = cfg.vulkan_probe.api_version >= kMinVulkanApiVersion;
    const bool vk_compute_verified = cfg.vulkan_probe.has_compute_queue &&
                                     cfg.vulkan_probe.max_compute_work_group_invocations >= kMinComputeWorkGroupInvocations;
    const bool vk_16bit_verified = cfg.vulkan_probe.has_khr_16bit_storage_ext &&
                                   cfg.vulkan_probe.storage_buffer_16bit_access;
    const bool driver_reliable = cfg.vulkan_probe.vulkan_library_loaded &&
                                 cfg.vulkan_probe.physical_device_found &&
                                 !cfg.vulkan_probe.driver_unreliable &&
                                 !is_software_vulkan_device(cfg.vulkan_probe);
    oss << "ggml_vulkan: 0 = " << cfg.vulkan_probe.device_name
        << " | api=" << format_vk_version(cfg.vulkan_probe.api_version)
        << " | compute_queue=" << (vk_compute_verified ? 1 : 0)
        << " | VK_KHR_16bit_storage=" << (cfg.vulkan_probe.has_khr_16bit_storage_ext ? 1 : 0)
        << " | 16bit_storage: " << (cfg.vulkan_probe.storage_buffer_16bit_access ? 1 : 0)
        << " | maxPushConstantsSize=" << cfg.vulkan_probe.max_push_constants_size
        << "\n";
    oss << "rpc_backend_gpu_enabled=" << (cfg.gpu_backend_enabled ? 1 : 0) << "\n";
    oss << "rpc_backend_zero_fallback_cpu=" << ((!cfg.gpu_backend_enabled && cfg.mode == RpcBackendMode::ARMV8_0_NEON_CPU) ? 1 : 0) << "\n";
    oss << "rpc_backend_vk_12_plus=" << (vk_12_plus ? 1 : 0) << "\n";
    oss << "rpc_backend_vk_compute=" << (vk_compute_verified ? 1 : 0) << "\n";
    oss << "rpc_backend_vk_16bit_storage=" << (vk_16bit_verified ? 1 : 0) << "\n";
    oss << "rpc_backend_vk_device_create=" << (cfg.vulkan_probe.device_create_verified ? 1 : 0) << "\n";
    oss << "rpc_backend_driver_reliable=" << (driver_reliable ? 1 : 0) << "\n";
    oss << "rpc_backend_device=" << cfg.device_arg << "\n";
    oss << "rpc_backend_cpu_isa=" << cfg.cpu_isa_target << "\n";
    oss << "rpc_backend_threads=" << cfg.cpu_threads << "\n";
    oss << "rpc_backend_reason=" << cfg.reason << "\n";
    return oss.str();
}

/**
 * Primary RPC initialization entrypoint called at rpc_server startup.
 * Probes VkPhysicalDeviceProperties, evaluates VK_KHR_16bit_storage & maxPushConstantsSize,
 * explicitly disables GPU backend if unsupported, and binds the ARMv8.0-A NEON CPU path.
 */
inline RpcBackendConfig initialize_rpc_server_backend(
    int32_t requested_threads,
    bool force_cpu_override = false
) {
    const VulkanPhysicalDeviceProbe probe = probe_vulkan_physical_device_properties();
    const RpcBackendConfig cfg = evaluate_vulkan_physical_device(
        probe,
        requested_threads,
        force_cpu_override
    );
    apply_rpc_backend_environment(cfg);

#if defined(SILICA_HAS_ANDROID_LOG)
    __android_log_print(
        ANDROID_LOG_INFO,
        "SilicaRpcNative",
        "Init backend=%s gpu_enabled=%d threads=%d maxPushConstantsSize=%u 16bit_storage=%d (%s)",
        cfg.device_arg.c_str(),
        cfg.gpu_backend_enabled ? 1 : 0,
        cfg.cpu_threads,
        cfg.vulkan_probe.max_push_constants_size,
        cfg.vulkan_probe.storage_buffer_16bit_access ? 1 : 0,
        cfg.reason.c_str()
    );
#endif
    return cfg;
}

} // namespace silica

// -----------------------------------------------------------------------------
// JNI Bridge for Android RpcService.kt (com.example.service.NativeVulkanBridge)
// -----------------------------------------------------------------------------
#if defined(SILICA_HAS_JNI)
extern "C" JNIEXPORT jstring JNICALL
Java_com_example_service_NativeVulkanBridge_nativeProbePhysicalDeviceProperties(
    JNIEnv* env,
    jobject /* thiz */,
    jint requestedThreads
) {
    const silica::RpcBackendConfig cfg = silica::initialize_rpc_server_backend(
        static_cast<int32_t>(requestedThreads),
        false
    );
    const std::string report = silica::format_probe_report(cfg);
    return env->NewStringUTF(report.c_str());
}
#endif

// -----------------------------------------------------------------------------
// Standalone CLI entry point for librpc_server_exec.so startup probing / init
// -----------------------------------------------------------------------------
#if !defined(RPC_SERVER_LIBRARY_ONLY)
int main(int argc, char** argv) {
    int32_t threads = 0; // 0 = auto-select optimal multi-threaded NEON core budget
    bool force_cpu = false;
    bool probe_only = false;

    for (int i = 1; i < argc; ++i) {
        if ((std::strcmp(argv[i], "-t") == 0 || std::strcmp(argv[i], "--threads") == 0) && i + 1 < argc) {
            threads = std::max<int32_t>(1, std::atoi(argv[++i]));
        } else if (std::strcmp(argv[i], "-d") == 0 && i + 1 < argc) {
            if (silica::to_lower_ascii(argv[++i]) == "cpu") {
                force_cpu = true;
            }
        } else if (std::strcmp(argv[i], "--disable-gpu") == 0) {
            force_cpu = true;
        } else if (std::strcmp(argv[i], "--probe-vulkan-props") == 0 ||
                   std::strcmp(argv[i], "--list-devices") == 0) {
            probe_only = true;
        }
    }

    const silica::RpcBackendConfig cfg = silica::initialize_rpc_server_backend(threads, force_cpu);
    std::cout << silica::format_probe_report(cfg);

    if (probe_only) {
        return 0;
    }

    // In full llama.cpp NDK builds, llama_rpc_start_server() binds here with:
    //   backend = cfg.gpu_backend_enabled ? ggml_backend_vk_init(0) : ggml_backend_cpu_init()
    //   ggml_backend_cpu_set_n_threads(backend, cfg.cpu_threads)
    return 0;
}
#endif
