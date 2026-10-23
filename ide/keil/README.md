# Keil MDK（µVision 5）接入

Keil 没有工程级的「外部工具」文件，自定义菜单项存在 **全局配置** 里，所以配一次之后所有工程都能用。

## 配置步骤

1. 打开 Keil，菜单 **Tools → Customize Tools Menu…**
2. 在对话框里填这几项（可以加多条，最多 20 条）：

| 字段 | 填什么 |
| --- | --- |
| Menu Content | `yuci_com 抓串口 10s` |
| Command | `C:\Windows\System32\cmd.exe` |
| Initial Folder | `$P` |
| Arguments | `/c "C:\coding\yuci_com\tools\yuci-monitor.cmd" -t 10` |

3. **Run Independent** 的取舍：
   - **不勾**：Keil 会等命令跑完（10 秒内点不了别的），但输出显示在 **Build Output** 窗口里，不用切窗口
   - **勾**：立刻返回，抓包在独立 cmd 窗口里跑，Keil 不卡
4. 确定后，`Tools` 菜单底部出现这条命令，点一下就开始抓。

## 其他几条常用命令

同样方式再加几条，只改 Arguments：

```bat
/c "C:\coding\yuci_com\tools\yuci-monitor.cmd" -t 5 -x
/c "C:\coding\yuci_com\tools\yuci-monitor.cmd" -t 0
/c "C:\coding\yuci_com\tools\yuci-monitor.cmd" -g
/c "C:\coding\yuci_com\tools\yuci-monitor.cmd" -l
```

## 说明

- `Initial Folder` 填 `$P` 是为了让 `-o serial_capture.log` 落在工程目录。如果这个宏在你机器上不生效（日志跑到了别处），直接留空即可。
- Keil 的 Build Output 窗口是 ANSI 编码，脚本里的中文提示可能显示为乱码；抓到的**数据本身**是 ASCII（比如 `t=123000 ms`），不受影响。想要完整中文就用 `-g` 打开图形界面。
- 如果不想改 Keil 全局配置，也可以直接在 Keil 里按 `Ctrl+Shift+W` 打开外部命令行窗口，手敲同样的命令。
