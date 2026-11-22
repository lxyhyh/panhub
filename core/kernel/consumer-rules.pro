# JNI 通过方法名反向查找，NativeKernel 的 external 声明不能被裁剪或改名。
-keepclasseswithmembernames class com.panhub.core.kernel.NativeKernel {
    native <methods>;
}
