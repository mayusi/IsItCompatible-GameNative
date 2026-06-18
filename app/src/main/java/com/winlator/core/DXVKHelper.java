package com.winlator.core;

import android.content.Context;

import com.winlator.core.envvars.EnvVars;
import com.winlator.xenvironment.ImageFs;

import java.io.File;

public class DXVKHelper {
    public static final String DEFAULT_CONFIG = "version="+DefaultVersion.DXVK+",framerate=0,maxDeviceMemory=0";

    public static KeyValueSet parseConfig(Object config) {
        String data = config != null && !config.toString().isEmpty() ? config.toString() : DEFAULT_CONFIG;
        return new KeyValueSet(data);
    }

    public static void setEnvVars(Context context, KeyValueSet config, EnvVars envVars) {
        ImageFs imageFs = ImageFs.find(context);
        envVars.put("DXVK_STATE_CACHE_PATH", imageFs.getRootDir().getPath()+ImageFs.CACHE_PATH);
        envVars.put("DXVK_LOG_LEVEL", "none");

        File rootDir = ImageFs.find(context).getRootDir();
        File dxvkConfigFile = new File(imageFs.config_path+"/dxvk.conf");

        String content = "\"";
        String maxDeviceMemory = config.get("maxDeviceMemory");
        if (!maxDeviceMemory.isEmpty() && !maxDeviceMemory.equals("0")) {
            content += "dxgi.maxDeviceMemory = "+maxDeviceMemory+"\n";
            content += "dxgi.maxSharedMemory = "+maxDeviceMemory+"\n";
        }

        String maxFeatureLevel = config.get("maxFeatureLevel");
        if (!maxFeatureLevel.isEmpty() && !maxFeatureLevel.equals("0")) {
            content += "d3d11.maxFeatureLevel = "+maxFeatureLevel+"\n";
            envVars.put("DXVK_FEATURE_LEVEL", maxFeatureLevel);
        }


        String framerate = config.get("framerate");
        if (!framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("DXVK_FRAME_RATE", framerate);
        }
        String customDevice = config.get("customDevice");
        if (customDevice.contains(":")) {
            String[] parts = customDevice.split(":");
            content = (((((content + "dxgi.customDeviceId = " + parts[0] + "\n") + "dxgi.customVendorId = " + parts[1] + "\n") + "d3d9.customDeviceId = " + parts[0] + "\n") + "d3d9.customVendorId = " + parts[1] + "\n") + "dxgi.customDeviceDesc = \"" + parts[2] + "\"\n") + "d3d9.customDeviceDesc = \"" + parts[2] + "\"\n";
        }
        if (config.getBoolean("constantBufferRangeCheck")) {
            content = content + "d3d11.constantBufferRangeCheck = \"True\"\n";
        }

        String async = config.get("async");
        if (!async.isEmpty() && !async.equals("0"))
            envVars.put("DXVK_ASYNC", "1");

        String asyncCache = config.get("asyncCache");
        if (!asyncCache.isEmpty() && !asyncCache.equals("0"))
            envVars.put("DXVK_GPLASYNCCACHE", "1");
        content = content + '\"';


        envVars.put("DXVK_CONFIG_FILE", rootDir + ImageFs.CONFIG_PATH+"/dxvk.conf");
        envVars.put("DXVK_CONFIG", content);
    }

    public static void setVKD3DEnvVars(Context context, KeyValueSet config, EnvVars envVars, String containerId) {
        String featureLevel = config.get("vkd3dFeatureLevel", "12_1");
        envVars.put("VKD3D_FEATURE_LEVEL", featureLevel);

        // Per-container VKD3D pipeline-cache dir — eliminates DX12 cold-start stutter by
        // persisting compiled pipeline state across launches (the var is declared in
        // EnvVarInfo but was never set). Isolated per container id so caches don't collide.
        ImageFs imageFs = ImageFs.find(context);
        File vkd3dCacheDir = new File(imageFs.cache_path + "/vkd3d-" + containerId);
        vkd3dCacheDir.mkdirs();
        envVars.put("VKD3D_SHADER_CACHE_PATH", vkd3dCacheDir.getPath());

        // Frame-rate cap for DX12 games. DXVK_FRAME_RATE only limits D3D9/10/11 (the DXVK
        // path); D3D12 goes through VKD3D-Proton, which has its own VKD3D_FRAME_RATE (v2.14+).
        // Without this, DX12 games had NO frame cap at all — they ran uncapped, cooking the
        // device and draining battery. We honour the same "framerate" container setting the
        // user already sets for DXVK so one Frame Limit control covers every renderer.
        String framerate = config.get("framerate");
        if (framerate != null && !framerate.isEmpty() && !framerate.equals("0")) {
            envVars.put("VKD3D_FRAME_RATE", framerate);
        }

        // Lower swapchain latency (default is 3 frames) for snappier input on a handheld.
        // Only set when the user hasn't overridden it via the env-var picker.
        if (!envVars.has("VKD3D_SWAPCHAIN_LATENCY_FRAMES")) {
            envVars.put("VKD3D_SWAPCHAIN_LATENCY_FRAMES", "2");
        }

        // ARM/mobile-GPU VKD3D tuning defaults (non-overriding). These are safe, universally
        // beneficial on a UMA mobile GPU:
        //   nodxr           - skip ray-tracing extension probing (no Adreno/Mali/Turnip DXR)
        //   single_queue    - match the single hardware queue on mobile GPUs, drop scheduler overhead
        //   no_upload_hvv   - don't use host-visible VRAM uploads (no benefit on unified memory)
        //   recycle_command_pools - reuse Vulkan command pools, less per-frame allocation
        //   memory_allocator_skip_clear - don't zero fresh allocations (small repeated win)
        // Merged with any user VKD3D_CONFIG rather than clobbering it.
        String armDefaults = "nodxr,single_queue,no_upload_hvv,recycle_command_pools,memory_allocator_skip_clear";
        String existingVkd3dConfig = envVars.get("VKD3D_CONFIG");
        if (existingVkd3dConfig == null || existingVkd3dConfig.isEmpty()) {
            envVars.put("VKD3D_CONFIG", armDefaults);
        } else {
            envVars.put("VKD3D_CONFIG", existingVkd3dConfig + "," + armDefaults);
        }
    }
}
