package com.npuhub.jni.openvino;

import com.npuhub.jni.NativeLibraryLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OpenVinoNativeBridge {
    private static final Logger log = LoggerFactory.getLogger(OpenVinoNativeBridge.class);
    private static boolean loaded = false;

    static {
        loaded = NativeLibraryLoader.loadLibrary("npu_openvino_jni");
    }

    public static boolean isLibraryLoaded() {
        return loaded;
    }

    // Native JNI functions implemented in C++ (libnpu_openvino_jni.so)
    public static native boolean nativeCheckDeviceAvailable();
    public static native String nativeGetDeviceName();
    public static native boolean nativeLoadModel(String modelPath, int contextWindow);
    public static native boolean nativeUnloadModel();
    public static native String nativeGenerate(String prompt, double temperature, double topP, int maxTokens);
    public static native void nativeGenerateStream(String prompt, double temperature, double topP, int maxTokens, StreamCallback callback);

    @FunctionalInterface
    public interface StreamCallback {
        void onToken(String token, boolean done);
    }
}
