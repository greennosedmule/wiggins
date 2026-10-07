# From upstream's proguard-rules.pro: the JNI functions in vad_jni.c are bound by the
# class and method names, so R8 mustn't rename or remove them.
-keep class com.konovalov.vad.** {*;}
