# v2.2 —— 接进你的 IDE

这一版没动核心功能，补的是「怎么让它待在编辑器里不来回切窗口」。

## IDE 接入

新增 `tools/yuci-monitor.cmd` 包装器 + `ide/` 下五套现成配置：

| IDE | 接入方式 | 文件 |
| --- | --- | --- |
| CLion | External Tools | `ide/clion/External Tools.xml` |
| IntelliJ IDEA / PyCharm / GoLand | External Tools（同一份通用） | 同上 |
| VS Code | Task，`Ctrl+Shift+B` | `ide/vscode/tasks.json` |
| Keil µVision 5 | Tools → Customize Tools Menu | `ide/keil/README.md` |
| STM32CubeIDE / Eclipse | Run → External Tools | `ide/stm32cubeide/` |

配好之后，烧录完点一下菜单，串口输出直接刷在 IDE 自己的输出窗口里。

## 包装器

```bat
yuci-monitor.cmd                 自动识别端口，抓 10 秒
yuci-monitor.cmd -p COM5 -t 30   指定端口与时长
yuci-monitor.cmd -t 5 -x         HEX 模式
yuci-monitor.cmd -t 0            一直抓，手动停止
yuci-monitor.cmd -t 20 -o a.txt  同时写文件，控制台照样实时刷
yuci-monitor.cmd -g              打开图形界面
yuci-monitor.cmd -l              列串口
```

自动识别只认 USB-UART 桥（CH340 / CP210 / FTDI / STLink / Virtual COM 等）；
机器上只有一个串口时直接用；多个口又都不匹配就报错要你 `-p` 指定 ——
Windows 上 COM3/COM4 常是蓝牙占位口，连上去什么也收不到，所以不做猜测。

## 命令行改进

- `--seconds 0`：不限时长，靠停止按钮 / `Ctrl+C` 结束
- `--out` 改成双写：控制台实时输出 + 文件同时落盘
  （2.1.1 及以前带 `--out` 时控制台是空的）

## 修复

- Windows 控制台中文乱码：打包补 `-Dstdout.encoding=UTF-8` / `-Dstderr.encoding=UTF-8`。
  之前在 CLion 的 Run 窗口里，`--list` 的端口描述显示成一串问号方块。
  只在 bat 里 `chcp 65001` 是没用的，必须落到 jpackage 的 java-options。

## 验证

自检 33 项全过。真机（STM32F103 + CH340，115200 8N1）实测：

```
[yuci] port=COM5 baud=115200 time=4s hex=
-- opened COM5 @115200 8N1 for 4s --
t=1035000 ms
t=1036000 ms
t=1037000 ms
t=1038000 ms
-- closed, received data --
```

HEX 模式、`-o` 落盘、`-t 0` 无限抓、板子拔掉后拒绝猜测蓝牙口，均验证通过。

---

# v2.1.1 —— 真机联调修复

> **重要：2.1 在真实串口下收不到任何数据，请升级到 2.1.1。**

在 STM32F103 + CH340（115200 8N1）上做的第一次真机联调，暴露了两个自检覆盖不到的问题：

**1. 打开串口后接收线程 100 ms 就死掉（严重）**

`SerialEngine` 原来用 `port.getInputStream()` 读数据。jSerialComm 的 `InputStream` 包装在
`TIMEOUT_READ_SEMI_BLOCKING` 模式下，**读超时会抛 `SerialPortTimeoutException` 而不是返回 0**。
于是总线一空闲超过 100 ms，接收线程就被异常打断，回调 `onClosed("读取中断…")`——
表现就是「状态栏显示已连接，但一条数据也收不到」。

改为直接调 `SerialPort.readBytes()`：超时返回 `0` 当作空闲继续循环，返回 `-1` 才判定为设备断开。

**2. 「实际参数」永远显示 9600 8N1（显示错误）**

打开后回读参数用的是 `SerialPort.getCommPort(name)` 新建的对象，拿到的是默认值。
改为从已打开的那个实例读回。

## 验证

```
$ yuci_com.exe --headless --port COM5 --seconds 4
-- opened COM5 @115200 8N1 for 4s --
t=747000 ms
t=748000 ms
t=749000 ms
t=750000 ms
```

```
$ yuci_com.exe --headless --port COM5 --seconds 3 --hex
74 3D 37 35 32 30 30 30 20 6D 73 0D 0A     →  t=752000 ms\r\n
```

界面侧（离屏驱动，不注入任何输入事件）：状态栏「已连接 COM5 · 115200 8N1」，
接收区带时间戳显示心跳，发送 `HELLO_GUI` 后原样回显。

---

# v2.1 —— 功能补齐与开源首发

首个开源版本。从「能用」推到「和 ATK-XCOM 一个量级」，并修掉两个真实缺陷。

## 新增

**发送框占位提示**
空的时候显示灰色 `请输入文本（Ctrl+Enter 发送）`，勾上 HEX 发送会自动换成 `请输入 HEX，例如 AA 55 01 02`。

**接收区背景图**
- 三个入口：菜单「背景 → 选择图片…」、接收工具栏「背景…」、**从剪贴板粘贴截图**
- 四种铺法：拉伸铺满 / 等比适应 / 居中 / 平铺
- 遮罩浓度 0~85% 可调，花哨的图调高就压得住文字
- 按可见区域绘制，滚动日志时背景固定不跟着跑
- 路径写入配置，下次启动自动恢复；「最近使用」保留 6 张

**快捷发送（多页发送）**
底部页签，列表双击即发，支持文本/HEX 混排、每条独立行尾、上下移排序，可一键**全部循环轮询**，适合长稳测试。数据存 `~/.yuci_com/quicksend.properties`。

**字符编码与流式解码**
UTF-8 / GBK / GB18030 / GB2312 / ISO-8859-1 / ASCII 六种接收编码。解码是流式的，**一个汉字被切在两个数据包里也不会乱码**。

**接收断帧**
超过设定的包间隔就自动换行，一行一帧，抓协议帧不用再自己数。

**数据统计与实时速率**
字节数、收发次数、平均速率、连接时长，状态栏实时刷新。

**界面**
- 上下分栏改成 `JSplitPane`，可拖动
- 菜单从 5 组扩到 7 组：文件 / 编辑 / 串口 / 工具 / 视图 / 背景 / 帮助，全部配了加速键
- 串口菜单内可直接切波特率、数据位、校验、停止位、流控
- 新增「发送 BREAK」「丢弃接收缓冲」「配置导入导出」「全屏」

## 修复

- **发送框里中文显示成方块**：`JTextArea` 的 `PlainView` 走 `Graphics.drawChars`，不做字体 fallback，而 Consolas 没有中文字形（`JTextPane` 走 `TextLayout` 所以接收区正常）。改为自动挑选中英文都能显示的等宽字体，并在「视图 → 字体」提供三档切换。
- **深色接收区默认前景色缺失**：底色是 `#1E1E1E` 但前景色沿用系统默认，部分主题下变成黑字黑底。
- 状态栏计数标签被同时添加到两个容器，导致布局抖动。

## 构建

- `build.bat` / `build.sh` 自动探测 JDK，产物输出到 `dist/`
- jpackage 裁掉未使用的运行时模块（`--add-modules java.base,java.desktop,java.logging,jdk.unsupported`），app-image 从 151 MB 降到 **73 MB**
- 发布包 `yuci_com-2.1-win-x64.zip` 约 **25 MB**
- 自检从 20 项扩到 **33 项**，`yuci_com.exe --selftest` 退出码 0

## 下载

| 文件 | 说明 |
|---|---|
| `yuci_com-2.1-win-x64.zip` | Windows x64，解压即用，自带 JRE |
| `YuciCom.java` | 单文件源码，零框架依赖 |

## 校验

```
yuci_com.exe --selftest
```

33 项全部 OK 才算构建通过，CI 里也是这么跑的。
