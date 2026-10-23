# 在各家 IDE 里用 yuci_com

所有 IDE 共用同一个入口：**`tools/yuci-monitor.cmd`**。IDE 只是负责把它挂到一个菜单或
任务上，输出落在 IDE 自己的输出窗口里。

```bat
yuci-monitor.cmd                 REM 自动识别 USB 串口，抓 10 秒
yuci-monitor.cmd -p COM5 -t 30   REM 指定端口与时长
yuci-monitor.cmd -t 5 -x         REM HEX
yuci-monitor.cmd -t 0            REM 一直抓，手动停止
yuci-monitor.cmd -t 20 -o a.txt  REM 同时写文件，控制台照样实时刷
yuci-monitor.cmd -g              REM 打开图形界面
yuci-monitor.cmd -l              REM 列串口
```

自动识别规则：

1. 先认 USB-UART 桥芯片：CH340 / CH341 / CH9102 / CP210 / FTDI / USB-SERIAL / Prolific /
   Silicon Labs / STLink / ST-Link / Virtual COM / Maple
2. 机器上**只有一个**串口时直接用它
3. 有多个口又都不匹配，**报错让你 `-p` 指定**，不会瞎猜（Windows 上 COM3/COM4 常是蓝牙占位口，
   连上去什么也收不到）

## 各 IDE 怎么挂

| IDE | 接入方式 | 配置文件 | 调用入口 |
| --- | --- | --- | --- |
| CLion | External Tools | `ide/clion/External Tools.xml` | `Tools → External Tools` |
| IntelliJ IDEA / PyCharm / GoLand | External Tools（同一份 XML 通用） | 同上 | `Tools → External Tools` |
| VS Code | Task | `ide/vscode/tasks.json` | `Ctrl+Shift+B` 或 `Terminal → Run Task` |
| Keil µVision 5 | Customize Tools Menu | 见 `ide/keil/README.md` | `Tools` 菜单底部 |
| STM32CubeIDE / Eclipse | External Tools | `ide/stm32cubeide/` | `Run → External Tools` |
| 任意终端 | 直接调用 | 无 | 见下方 |

## JetBrains 全家桶

CLion、IDEA、PyCharm 的 External Tools 是同一套机制，`ide/clion/External Tools.xml` 可以直接复用。

**只给一个工程用**：拷到工程根目录的 `.idea/tools/`（目录不存在就新建），重载项目生效。

**给所有工程用**（推荐）：

```bat
copy "C:\coding\yuci_com\ide\clion\External Tools.xml" ^
     "%APPDATA%\JetBrains\CLion2026.2\tools\External Tools.xml"
```

把 `CLion2026.2` 换成你实际的版本目录（`%APPDATA%\JetBrains` 下能看到）。
IDEA 等其他 JetBrains IDE 同理，换成对应目录名即可。

> 注意：项目级和全局同时存在会出现两份重复菜单，二选一。

## VS Code

把 `ide/vscode/tasks.json` 拷到工程根目录的 `.vscode/`，然后：

- `Ctrl+Shift+B` 直接跑默认任务
- 或 `Terminal → Run Task…` 选具体条目（抓 10 秒 / HEX / 落盘 / 一直抓 / GUI / 列端口）

想绑快捷键：`Ctrl+K Ctrl+S` → 搜 `workbench.action.tasks.runTask` → 指定任务名。

## Keil / STM32CubeIDE

见各自目录下的 README，都是 3~4 步的 GUI 配置。

## 任意终端

**cmd**

```bat
C:\coding\yuci_com\tools\yuci-monitor.cmd -t 10
```

**PowerShell**

```powershell
& "C:\coding\yuci_com\tools\yuci-monitor.cmd" -t 10
```

**Git Bash / WSL**（脚本是 cmd 的，用 cmd 包一层，或直接调 exe）

```bash
cmd //c "C:/coding/yuci_com/tools/yuci-monitor.cmd" -t 10
# 或者直接：
/c/coding/yuci_com/dist/yuci_com/yuci_com.exe --headless --port COM5 --seconds 10
```

## 常见问题

**打开失败：errcode=5 / 端口被占用**
串口是独占资源，XCOM、Arduino 串口监视器、上次没关干净的 `yuci_com.exe` 都会占着。
查占用：

```powershell
Get-CimInstance Win32_Process | ? { $_.Name -eq 'yuci_com.exe' } | % { Stop-Process -Id $_.ProcessId -Force }
```

**中文显示成方块**
`--list` 的端口描述靠打包参数 `-Dstdout.encoding=UTF-8` 保证，2.2 起已内置。如果用的是旧版
exe，端口描述会乱码，但抓到的数据（`t=123000 ms` 之类）不受影响。

**IDE 里找不到菜单项**
- CLion / IDEA：确认文件放在 `.idea/tools/`，且 IDE 是**重载项目之后**才读的（改的时候 IDE 最好先关）
- VS Code：确认 `tasks.json` 在工程根的 `.vscode/` 里，且 JSON 没有语法错误
- Keil：Customize Tools Menu 是全局设置，配完所有工程都有

**抓不到数据**
1. `-l` 确认端口号对不对
2. 确认 TX/RX 是交叉接的、GND 共地
3. 确认波特率和固件一致
4. 板子单独供电时，USB-TTL 的 VCC 不要接
