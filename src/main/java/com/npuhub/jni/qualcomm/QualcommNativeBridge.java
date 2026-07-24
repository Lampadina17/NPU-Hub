package com.npuhub.jni.qualcomm;

import com.npuhub.jni.NativeLibraryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class QualcommNativeBridge {
    private static final Logger log = LoggerFactory.getLogger(QualcommNativeBridge.class);
    private static boolean loaded = false;

    static {
        loaded = NativeLibraryLoader.loadLibrary("npu_qualcomm_jni");
    }

    public static boolean isLibraryLoaded() {
        return loaded;
    }

    public static native boolean nativeCheckGenieAvailable();
    public static native boolean nativeLoadGenieModel(String modelPath, int contextWindow);
    public static native boolean nativeUnloadGenieModel();
    public static native String nativeGenerateGenie(String prompt, double temperature, double topP, int maxTokens);
    public static native void nativeGenerateGenieStream(String prompt, double temperature, double topP, int maxTokens, StreamCallback callback);

    @FunctionalInterface
    public interface StreamCallback {
        void onToken(String token, boolean done);
    }
}
