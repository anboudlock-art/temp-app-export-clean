#
# This ProGuard configuration file illustrates how to process Android
# applications.
# Usage:
#     java -jar proguard.jar @android.pro
#
# If you're using the Android SDK (version 2.3 or higher), the android tool
# already creates a file like this in your project, called proguard.cfg.
# It should contain the settings of this file, minus the input and output paths
# (-injars, -outjars, -libraryjars, -printmapping, and -printseeds).
# The generated Ant build file automatically sets these paths.

-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# Save the obfuscation mapping to a file, so you can de-obfuscate any stack
# traces later on.

#-printmapping bin/classes-processed.map

# You can print out the seeds that are matching the keep options below.

#-printseeds bin/classes-processed.seeds

# Preverification is irrelevant for the dex compiler and the Dalvik VM.

-dontpreverify

# Reduce the size of the output some more.

-repackageclasses ''
-allowaccessmodification

# Switch off some optimizations that trip older versions of the Dalvik VM.

-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*
#-dontoptimize

-keepattributes *Annotation*

# Preserve all fundamental application classes.

#-keep public class * extends android.app.Activity
#-keep public class * extends android.app.Application
#-keep public class * extends android.app.Service
#-keep public class * extends android.content.BroadcastReceiver
#-keep public class * extends android.content.ContentProvider
-keep public class * extends android.support.v4.**

# Preserve all View implementations, their special context constructors, and
# their setters.

-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Preserve all classes that have special context constructors, and the
# constructors themselves.

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}

# Preserve all classes that have special context constructors, and the
# constructors themselves.

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Preserve the special fields of all Parcelable implementations.

-keepclassmembers class * implements android.os.Parcelable {
    static android.os.Parcelable$Creator CREATOR;
}

#add for event bus
-keepclassmembers class ** {
    public void onEvent*(**);
    void onEvent*(**);
}

# Preserve static fields of inner classes of R classes that might be accessed
# through introspection.

-keepclassmembers class **.R$* {
  public static <fields>;
}

# Preserve the required interface from the License Verification Library
# (but don't nag the developer if the library is not used at all).

-keep public interface com.android.vending.licensing.ILicensingService

-dontnote com.android.vending.licensing.ILicensingService

# The Android Compatibility library references some classes that may not be
# present in all versions of the API, but we know that's ok.
#-ignorewarnings
-dontwarn android.support.**
-dontwarn com.google.**
-dontwarn javax.annotation.processing.**
-dontwarn javax.tools.**
-dontwarn javax.lang.model.**
-dontwarn org.apache.http.annotation.**
-dontwarn org.ietf.jgss.**
-dontwarn org.joda.convert.**
-dontwarn org.w3c.dom.**
#for picasso
-dontwarn com.squareup.okhttp.**
-dontwarn org.codehaus.mojo.**
-dontwarn java.nio.file.**

-dontwarn com.weibo.sdk.**
-dontwarn com.baidu.**
-dontwarn com.amazonaws.**
# Preserve all native method names and the names of their classes.

-keepclasseswithmembernames class * {
    native <methods>;
}

# Preserve the special static methods that are required in all enumeration
# classes.

-keepclassmembers class * extends java.lang.Enum {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Explicitly preserve all serialization members. The Serializable interface
# is only a marker interface, so it wouldn't save them.
# You can comment this out if your application doesn't use serialization.
# If your code contains serializable classes that have to be backward
# compatible, please refer to the manual.

-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# Your application may contain more items that need to be preserved;
# typically classes that are dynamically created using Class.forName:

# -keep public class mypackage.MyClass
# -keep public interface mypackage.MyInterface
# -keep public class * implements mypackage.MyInterface

-keep class com.activeandroid.** {*;}
-keep class com.google.** { *; }
-keep class org.apache.**{ *; }

#for amazonaws
-keep class org.apache.commons.logging.** { *; }
-keep class com.amazonaws.** {*;}
-keep class com.fasterxml.jackson.** {*;}
-keep class org.apache.commons.** {*;}
-keep class org.codehaus.**
-keep class org.joda.** {*;}
-keepattributes Signature,*Annotation*

-keep class com.kochava.android.** {*;}
-keep class retrofit.** {*;}

#for weibo
-keep class com.sina.weibo.** {*;}
-keepclassmembers class fqcn.of.javascript.interface.for.webview {
   public *;
}
#for baidu lbs
-keep class com.baidu.** {*;}
-keep class vi.com.gdi.bgl.android.java.** {*;}
#for qiniu
-keep class com.qiniu.android.** {*;}

#for aviary sdk
-keep class com.aviary.android.feather.** {*;}

#for butterknife
-keep class butterknife.** { *; }
-dontwarn butterknife.internal.**
-keep class **$$ViewBinder { *; }
-keepclasseswithmembernames class * {
    @butterknife.* <fields>;
}
-keepclasseswithmembernames class * {
    @butterknife.* <methods>;
}

# for fastjson
-dontwarn com.alibaba.fastjson.**
-keep class com.alibaba.fastjson.** { *; }

#for android-async-http
-keep class com.loopj.android.http.** { *; }
-keep interface com.loopj.android.http.** { *; }

#for entities
-keep class com.chltec.blelock.module.entity.** { *; }
-keep class com.chltec.blelock.module.bean.** { *; }
-keep class com.chltec.blelock.db.dao.** { *; }

#for wechat
#-keep class com.tencent.mm.sdk.** { *; }

### greenDAO 3
-keepclassmembers class * extends org.greenrobot.greendao.AbstractDao {
public static java.lang.String TABLENAME;
}
-keep class **$Properties

# If you do not use SQLCipher:
-dontwarn org.greenrobot.greendao.database.**
# If you do not use RxJava:
-dontwarn rx.**

-dontwarn com.viewpagerindicator.**

#UMeng
-keepclassmembers class * {
   public <init> (org.json.JSONObject);
}
-keep public class [com.chltec.blelock].R$*{
public static final int *;
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class com.inuker.bluetooth.library.** { *; }
-keep class com.socks.library.** { *; }
-keep class com.dou361.dialogui.** { *; }

-dontwarn com.tencent.bugly.**
-keep public class com.tencent.bugly.**{*;}
