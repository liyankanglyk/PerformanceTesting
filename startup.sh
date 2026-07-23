#!/usr/bin/env bash
set -e

echo "========================================"
echo "  PerformanceTesting - 项目启动脚本"
echo "========================================"
echo ""

# --------------------- 项目目录 ---------------------
PROJECT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$PROJECT_DIR"

# --------------------- 检测 Java ---------------------
JAVA_CMD=""

if [ -n "$JAVA_HOME" ] && [ -x "$JAVA_HOME/bin/java" ]; then
    JAVA_CMD="$JAVA_HOME/bin/java"
    echo "[INFO] 使用 JAVA_HOME: $JAVA_HOME"
elif command -v java &>/dev/null; then
    JAVA_CMD="$(command -v java)"
    echo "[INFO] 使用 PATH 中的 Java: $JAVA_CMD"
else
    echo "[ERROR] 未找到 Java，请安装 JDK 17+ 并设置 JAVA_HOME 或添加到 PATH"
    exit 1
fi

JAVA_VER=$("$JAVA_CMD" -version 2>&1 | head -1 | grep -oP '"[^"]+"' | tr -d '"')
echo "[INFO] Java 版本: $JAVA_VER"
echo ""

# --------------------- 检测 Maven ---------------------
MVN_CMD=""

if [ -f "$PROJECT_DIR/mvnw" ]; then
    chmod +x "$PROJECT_DIR/mvnw" 2>/dev/null || true
    MVN_CMD="$PROJECT_DIR/mvnw"
    echo "[INFO] 使用 Maven Wrapper: mvnw"
elif command -v mvn &>/dev/null; then
    MVN_CMD="mvn"
    echo "[INFO] 使用系统 Maven"
else
    echo "[ERROR] 未找到 Maven，请安装 Maven 或生成 Maven Wrapper"
    echo "        生成命令: mvn wrapper:wrapper"
    exit 1
fi
echo ""

# --------------------- 读取配置 ---------------------
SPRING_PROFILES=""

# 可选: 从 startup-config.sh 读取自定义参数
if [ -f "$PROJECT_DIR/startup-config.sh" ]; then
    echo "[INFO] 加载 startup-config.sh ..."
    # shellcheck disable=SC1090
    source "$PROJECT_DIR/startup-config.sh"
fi

# --------------------- 编译 & 启动 ---------------------
echo "[INFO] 正在编译项目..."
"$MVN_CMD" compile -q -f "$PROJECT_DIR/pom.xml"
echo "[INFO] 编译完成"
echo ""

echo "[INFO] 正在启动应用..."
echo "========================================"
echo ""

"$MVN_CMD" spring-boot:run -f "$PROJECT_DIR/pom.xml" $SPRING_PROFILES

echo ""
echo "========================================"
echo "  应用已停止"
echo "========================================"
