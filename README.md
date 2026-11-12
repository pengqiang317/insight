# Insight

给自己的 Android 应用用的启动、界面、任务耗时和崩溃收集库。库是 Kotlin 写的，公开接口可以从 Java 直接调用，现有 Java 工程不用打开 Kotlin 插件。

minSdk 24。会带上 `kotlin-stdlib` 和 `androidx.fragment`。

## 引用

仓库在 [pengqiang317/insight](https://github.com/pengqiang317/insight)。其它工程用 JitPack 引用 tag `0.1.0`：

```gradle
maven { url 'https://jitpack.io' }
dependencies {
    implementation 'com.github.pengqiang317.insight:insight:0.1.0'
}
```

只加依赖就会开始计时，并接住 Java 崩溃。不配置服务器时，记录写在应用私有目录 `files/insight/outbox/`，调试包同时打到 Logcat，过滤 `Insight`。

需要上报或调整阈值时：

```java
Insight.configure(new InsightConfig.Builder()
        .endpoint("https://example.com/insight")
        .debug(BuildConfig.DEBUG)
        .slowTaskMs(100)
        .blockThresholdMs(200)
        .build());
```

应用里如果还有别的 Native 崩溃库，把它们的初始化放在 `Application.onCreate` 里。本库在 `onCreate` 返回之后才安装信号处理器，并转发给当时已经存在的处理器。

某一段任务：

```java
Insight.trace("解析首页", () -> parseHome());

InsightSpan span = Insight.begin("下载配置");
span.put("count", "12");
span.end();
```

界面内容真正准备好时：

```java
Insight.reportFullyDrawn(this);
```

## 记录什么

| type | 含义 |
|---|---|
| startup | 一次进前台。cold 含进程创建到第一帧；warm 是进程还在、界面重建；hot 是界面还在、回到前台。Android 15 且从桌面图标进来时，额外有点击到第一帧 |
| screen | Activity 或 Fragment 到第一帧。Android 10 及以上能拆开 create / start / resume |
| task | `trace` / `begin` 的耗时 |
| jank | 界面停留期间的慢帧和冻帧。慢帧按刷新率的两倍预算算，冻帧是 700ms |
| block | 主线程超过阈值没有处理消息，附主线程栈 |
| memory | 冷启动第一帧、退到后台时各一次，单位 KB |
| fully_drawn | 调用 `reportFullyDrawn` 时，进程创建到该调用 |
| java_crash | 未捕获 Java 异常，含栈和最近 20 条界面/任务 |
| native_crash | Native 崩溃。Android 7–11 来自本库的信号报告；Android 12 及以上同时带系统 tombstone |
| anr | Android 11 及以上的系统 ANR。Android 12 及以上含系统 trace |
| exit | 低内存或信号退出 |

Android 7–9 的界面耗时是「onCreate 返回之后到第一帧」，字段 `timing` 为 `afterCreate`。Android 10 及以上 `timing` 为 `api29`，create/start/resume 是对应生命周期本身的耗时。

点击图标到进程创建，只有 Android 15 及以上系统会提供。更低版本的启动数字是进程创建到第一帧。

## Native 崩溃

本库捕获 `SIGSEGV`、`SIGABRT`、`SIGBUS`、`SIGFPE`、`SIGILL`，把调用栈和映射写到文件，然后调用原来的处理器。Java 崩溃导致的 `abort` 不会再记成一条 Native 崩溃。

信号报告里的 `pc` 是 so 内偏移，可以用 NDK 的 `ndk-stack` 对带符号的 so 还原：

```bash
ndk-stack -sym <含符号 so 的目录> -dump native.txt
```

Android 12 以下读不到系统 tombstone，所以这条信号报告就是 Native 栈的来源。Android 12 及以上优先同时保留系统原文。

## 本地查看

```java
for (String line : Insight.pendingSummaries()) {
    Log.i("Insight", line);
}
```

`sample` 是一个 Java 示例：两个界面、一个 Fragment、一段 300ms 慢任务、Java 崩溃和 Native 崩溃。崩溃后重新打开，点「读取上次记录」。
