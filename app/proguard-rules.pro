# release 构建已开启 R8(isMinifyEnabled=true)与资源压缩(isShrinkResources=true)。
# 房间/Hilt/Compose/Glance 走生成代码直接引用,通常无需 keep;下面只保留真正依赖
# 「按名字反射」的入口。

# 保留注解与泛型签名(Room / Hilt / Firebase 反射路径需要)
-keepattributes *Annotation*, Signature, InnerClasses, EnclosingMethod, MethodParameters

# WebView 桥:适配脚本按方法名调用 AndroidBridgeNative.*,被 R8 改名就会整体失效
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keep class com.kxin.classtable.ui.importer.ImportBridge { *; }
# 雨课堂登录页的桥(同上,唯一入口是网页里的 AndroidYuketangNative.*)
-keep class com.kxin.classtable.ui.yuketang.YuketangLoginBridge { *; }

# Firebase Analytics / Crashlytics / Messaging 依赖反射与清单注册
-keep class com.google.firebase.messaging.** { *; }
-dontwarn com.google.firebase.**
-dontwarn com.google.android.gms.**

# org.json 由系统提供
-dontwarn org.json.**

# 小组件与通知组件由清单注册,保留其构造入口
-keep class * extends android.app.Service { *; }
-keep class * extends android.content.BroadcastReceiver { *; }
-keep class * extends androidx.work.ListenableWorker { *; }
