#!/bin/bash
set -e

# ============================================================
# VS Code Webview 编译脚本
# ============================================================

# --- 路径配置（请根据实际环境修改） ---
ANDROID_HOME="$HOME/Android/Sdk"
BUILD_TOOLS="$ANDROID_HOME/build-tools/36.0.0"
PLATFORM="$ANDROID_HOME/platforms/android-36/android.jar"

# --- 构建输出目录 ---
BUILD_DIR="build"

# --- 工具路径 ---
AAPT2="$BUILD_TOOLS/aapt2"
D8="$BUILD_TOOLS/d8"
ZIPALIGN="$BUILD_TOOLS/zipalign"
APKSIGNER="$BUILD_TOOLS/apksigner"

# --- 清理上次构建 ---
echo "=== 清理构建目录 ==="
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/compiled_res" "$BUILD_DIR/classes"

# ============================================================
# 步骤 1: aapt2 compile — 编译资源文件
# ============================================================
# 将 XML 资源（布局、字符串、样式等）编译为 AAPT2 的二进制中间格式 (.flat)
# 这一步是安卓独有的：普通 Java 项目没有资源编译这个概念
echo ""
echo "=== 步骤 1: aapt2 compile（编译资源）==="
$AAPT2 compile -o "$BUILD_DIR/compiled_res/" \
    "res/layout/activity_main.xml" \
    "res/values/strings.xml" \
    "res/values/styles.xml" \
    "res/mipmap-xxhdpi/ic_launcher.png"
echo "资源编译完成，生成 .flat 文件："
ls "$BUILD_DIR/compiled_res/"

# ============================================================
# 步骤 2: aapt2 link — 链接资源
# ============================================================
# 将编译后的资源链接在一起，生成：
#   1. 未签名的初始 APK（包含编译后的资源和 AndroidManifest.xml）
#   2. R.java — 资源 ID 映射文件，让 Java 代码可以通过 R.layout.xxx 引用资源
echo ""
echo "=== 步骤 2: aapt2 link（链接资源，生成 R.java）==="
$AAPT2 link -o "$BUILD_DIR/unaligned.apk" \
    -I "$PLATFORM" \
    --manifest "AndroidManifest.xml" \
    --java "$BUILD_DIR" \
    --auto-add-overlay \
    -A "assets" \
    "$BUILD_DIR/compiled_res/"*.flat
echo "R.java 生成位置："
find "$BUILD_DIR" -name "R.java"

# ============================================================
# 步骤 3: javac — 编译 Java 源码
# ============================================================
# 将 .java 文件编译为 .class 文件（JVM 字节码）
# -classpath 指向 android.jar，提供 Android API 的类定义
# -sourcepath 同时包含源码目录和 R.java 所在目录
echo ""
echo "=== 步骤 3: javac（编译 Java 源码）==="
javac \
    -source 17 -target 17 \
    -classpath "$PLATFORM" \
    -sourcepath "src:$BUILD_DIR" \
    -d "$BUILD_DIR/classes" \
    "$BUILD_DIR/org/MrZ/vscode_webview/R.java" \
    $(find "src" -name "*.java")
echo "编译完成，生成 .class 文件："
find "$BUILD_DIR/classes" -name "*.class"

# ============================================================
# 步骤 4: d8 — 将 .class 转换为 .dex
# ============================================================
# Android 不使用 JVM，而是使用自己的 Dalvik/ART 虚拟机
# d8 将标准 Java 字节码 (.class) 转换为 Dalvik 字节码 (.dex)
echo ""
echo "=== 步骤 4: d8（转换为 dex 字节码）==="
$D8 \
    --lib "$PLATFORM" \
    --output "$BUILD_DIR" \
    $(find "$BUILD_DIR/classes" -name "*.class")
echo "生成 classes.dex"

# ============================================================
# 步骤 5: 组装 APK
# ============================================================
# 将 classes.dex 塞入之前 aapt2 link 生成的 APK 中
echo ""
echo "=== 步骤 5: 组装 APK ==="
cp "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/code_webview-unsigned.apk"
(cd "$BUILD_DIR" && zip -u code_webview-unsigned.apk classes.dex)

# ============================================================
# 步骤 6: zipalign — 对齐 APK
# ============================================================
# 4 字节对齐优化，提高运行时内存映射效率
echo ""
echo "=== 步骤 6: zipalign（对齐 APK）==="
$ZIPALIGN -f 4 "$BUILD_DIR/code_webview-unsigned.apk" "$BUILD_DIR/code_webview-aligned.apk"
echo "对齐完成"

# ============================================================
# 步骤 7: apksigner — 签名 APK
# ============================================================
# Android 要求所有 APK 必须经过数字签名才能安装
# 此处会自动寻找项目根目录下的keystore文件
echo ""
echo "=== 步骤 7: apksigner（签名 APK）==="

function find_keystore() {
    local keystore_path
    keystore_path=$(find -maxdepth 1 -type f -name "*.keystore" | head -n 1)
    if [[ -z "$keystore_path" ]]; then
        echo "错误: 未找到 keystore 文件" >&2
        exit 1
    fi
    echo "$keystore_path"
}

$APKSIGNER sign \
    --ks "$(find_keystore)" \
    --out "$BUILD_DIR/code_webview.apk" \
    "$BUILD_DIR/code_webview-aligned.apk"
echo "签名完成: $BUILD_DIR/code_webview.apk"

echo ""
echo "============================================================"
echo "构建完成，APK产物位于: $BUILD_DIR/code_webview.apk"
echo "其它文件都是中间产物，忽略即可"
echo "如果连接了设备，可以使用命令安装：adb install $BUILD_DIR/code_webview.apk"
echo "============================================================"
