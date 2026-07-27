package com.npuhub.jni.rockchip;

import com.npuhub.jni.NativeLibraryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RockchipNativeBridge {
    private static final Logger log = LoggerFactory.getLogger(RockchipNativeBridge.class);
    private static boolean loaded = false;

    static {
        loaded = NativeLibraryLoader.loadLibrary("npu_rockchip_jni");
    }

    public static boolean isLibraryLoaded() {
        return loaded;
    }

    // Native JNI functions implemented in C++ (libnpu_rockchip_jni.so)
    public static native boolean nativeCheckAccel0Available();

    public static native String nativeGetRknnVersion();

    public static native void nativeSetLogCallback(NativeLogCallback callback);

    public static native boolean nativeLoadRocketModel(String modelPath, int contextWindow);

    public static native boolean nativeUnloadRocketModel();

    public static native String nativeGenerateRocket(
            String prompt,
            double temperature,
            double topP,
            int maxTokens,
            int topK,
            double minP,
            long seed,
            int repeatLastN,
            double repeatPenalty,
            double frequencyPenalty,
            double presencePenalty
    );

    public static native void nativeGenerateRocketStream(
            String prompt,
            double temperature,
            double topP,
            int maxTokens,
            int topK,
            double minP,
            long seed,
            int repeatLastN,
            double repeatPenalty,
            double frequencyPenalty,
            double presencePenalty,
            StreamCallback callback
    );

    @FunctionalInterface
    public interface StreamCallback {
        void onToken(String token, boolean done);
    }

    @FunctionalInterface
    public interface NativeLogCallback {
        void onLog(int level, String message);
    }
}
