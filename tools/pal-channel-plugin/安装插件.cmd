@echo off
REM DSH Pal 通道插件安装（双击运行）
REM
REM 为什么必须单独跑：pnpm install 会重装依赖，如果 DSH 正在运行，
REM 它会加载到半成品依赖树 —— 2026-11-04 就是这么炸的：
REM   The requested module './text.js' does not provide an export named 'ownedSlice'
REM 所以本脚本第一步就要求你确认 DSH 已关闭。
setlocal
chcp 936 >nul

echo ========================================
echo   DSH Pal 通道插件安装
echo ========================================
echo.
echo 请先完全关闭 DeepSeek Harness（托盘图标也要退出），
echo 然后按任意键继续。如果没关，现在按 Ctrl+C 取消。
echo.
pause >nul

echo.
echo [1/4] 检查 DSH 是否真的关了...
tasklist /FI "IMAGENAME eq DeepSeek Harness.exe" 2>nul | find /I "DeepSeek Harness.exe" >nul
if not errorlevel 1 (
  echo.
  echo [X] DSH 还在运行，请先完全退出再重试。
  echo     如果托盘里还有图标，右键退出。
  echo.
  pause
  exit /b 1
)
echo     已关闭，继续。

echo.
echo [2/4] 安装依赖（可能要 1-3 分钟）...
cd /d "%USERPROFILE%\.dsh\profiles\desktop"
call pnpm install
if errorlevel 1 (
  echo.
  echo [X] pnpm install 失败，往上翻看错误。
  echo     回滚方法：见 <工作区>\sessions\2026-11-03-DSHPal-对话本地缓存\交接-插件上架.md 第八节
  echo.
  pause
  exit /b 1
)

echo.
echo [3/4] 验证插件能否被解析...
node -e "import('dsh-pal-channel').then(function(m){console.log('    OK 导出: '+Object.keys(m).join(','))}).catch(function(e){console.log('    FAIL '+e.code);process.exit(1)})"
if errorlevel 1 (
  echo.
  echo [X] 插件解析失败。
  echo.
  pause
  exit /b 1
)

echo.
echo [4/4] 完成！
echo.
echo ========================================
echo  现在请启动 DeepSeek Harness
echo ========================================
echo.
echo 启动后检查两件事：
echo   1. 设置 - 插件 里能看到「DSH Pal 通道切换」（带图标）
echo   2. <工作区>\sessions\pal-channel-load.log 里有 apply called
echo.
echo 如果列表里没有，把上面那个 log 文件的内容告诉我。
echo.
pause
