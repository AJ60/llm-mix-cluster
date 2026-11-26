cmake_minimum_required(VERSION 3.22.1)
project(silica_rpc_server LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# ARMv8.2-A DOTPROD/I8MM runtime dispatch with ARMv8.0-A NEON baseline.
if(ANDROID_ABI STREQUAL "arm64-v8a" OR CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    add_compile_options(-march=armv8-a+simd -O3 -ffast-math -funroll-loops)
else()
    add_compile_options(-O3)
endif()

add_compile_options(-fexceptions)
add_link_options(-Wl,-z,max-page-size=16384)

option(SILICA_WITH_GGML "Vendor llama.cpp v1.0.0 ggml RPC/Vulkan backends" OFF)
set(SILICA_GGML_LIBS "")
if(SILICA_WITH_GGML)
    include(FetchContent)
    set(GGML_RPC ON CACHE BOOL "" FORCE)
    set(GGML_VULKAN ON CACHE BOOL "" FORCE)
    set(GGML_CPU_ALL_VARIANTS ON CACHE BOOL "" FORCE)
    set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(GGML_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    FetchContent_Declare(
        llama_cpp
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp
        GIT_TAG v1.0.0
        GIT_SHALLOW TRUE
    )
    FetchContent_MakeAvailable(llama_cpp)
    set(SILICA_GGML_LIBS ggml ggml-cpu ggml-rpc ggml-vulkan)
    add_compile_definitions(SILICA_WITH_GGML=1)
endif()

add_library(rpc_server_probe SHARED rpc_server.cpp)
target_compile_definitions(rpc_server_probe PRIVATE RPC_SERVER_LIBRARY_ONLY=1 SILICA_HAS_JNI=1)

find_library(log-lib log)
if(log-lib)
    target_link_libraries(rpc_server_probe PRIVATE ${log-lib} dl)
else()
    target_link_libraries(rpc_server_probe PRIVATE dl)
endif()

add_executable(rpc_server_exec rpc_server.cpp)
set_target_properties(rpc_server_exec PROPERTIES PREFIX "lib" SUFFIX ".so")
target_compile_definitions(rpc_server_exec PRIVATE SILICA_CPU_BASELINE=1)
if(log-lib)
    target_link_libraries(rpc_server_exec PRIVATE ${log-lib} dl ${SILICA_GGML_LIBS})
else()
    target_link_libraries(rpc_server_exec PRIVATE dl ${SILICA_GGML_LIBS})
endif()

add_executable(rpc_server_exec_v82 rpc_server.cpp)
set_target_properties(rpc_server_exec_v82 PROPERTIES PREFIX "lib" SUFFIX ".so")
if(ANDROID_ABI STREQUAL "arm64-v8a" OR CMAKE_SYSTEM_PROCESSOR MATCHES "aarch64|arm64")
    target_compile_options(rpc_server_exec_v82 PRIVATE -march=armv8.2-a+simd+dotprod+i8mm)
endif()
target_compile_definitions(rpc_server_exec_v82 PRIVATE SILICA_CPU_DOTPROD=1)
if(log-lib)
    target_link_libraries(rpc_server_exec_v82 PRIVATE ${log-lib} dl ${SILICA_GGML_LIBS})
else()
    target_link_libraries(rpc_server_exec_v82 PRIVATE dl ${SILICA_GGML_LIBS})
endif()

enable_testing()
add_executable(rpc_server_test rpc_server_test.cpp)
target_link_libraries(rpc_server_test PRIVATE dl)
add_test(NAME rpc_server_test COMMAND rpc_server_test)
