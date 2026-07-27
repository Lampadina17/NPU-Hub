package com.npuhub.jni.ryzenai;

import com.npuhub.jni.NativeLibraryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RyzenAiNativeBridge {
    private static final Logger log = LoggerFactory.getLogger(RyzenAiNativeBridge.class);
    private static boolean loaded = false;

    static {
        loaded = NativeLibraryLoader.loadLibrary("npu_ryzenai_jni");
    }

    public static boolean isLibraryLoaded() {
        return loaded;
    }

    public static native boolean nativeCheckXdnaAvailable();

    public static native boolean nativeLoadOnnxGenieModel(String modelPath, int contextWindow);

    public static native boolean nativeUnloadOnnxGenieModel();

    public static native String nativeGenerateOnnxGenie(String prompt, double temperature, double topP, int maxTokens);

    public static native void nativeGenerateOnnxGenieStream(String prompt, double temperature, double topP, int maxTokens, StreamCallback callback);

    @FunctionalInterface
    public interface StreamCallback {
        void onToken(String token, boolean done);
    }
}
