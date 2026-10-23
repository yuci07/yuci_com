# STM32CubeIDE / Eclipse 接入

Eclipse 系（STM32CubeIDE、SW4STM32、MCUXpresso）走 **External Tools**，配置文件是 `.launch`，
可以随工程走，也可以全局配一次。

## 方式一：GUI 配置（推荐，最稳）

1. 菜单 **Run → External Tools → External Tools Configurations…**
2. 左侧 `Program` 上右键 → **New Configuration**，名字填 `yuci_com 抓串口 10s`
3. **Main** 页：

| 字段 | 填什么 |
| --- | --- |
| Location | `C:\Windows\System32\cmd.exe` |
| Working Directory | `${project_loc}` |
| Arguments | `/c "C:\coding\yuci_com\tools\yuci-monitor.cmd" -t 10` |

4. **Common** 页 → 勾上 **Display in favorites menu → External Tools**
5. Apply → Run。之后工具栏下拉里就能一键调用。

## 方式二：导入 `.launch` 文件

把本目录的 `yuci_com.launch` 拷到工程根目录，然后在 **Run → External Tools → External Tools
Configurations…** 里右键 → **Import…** 选它。

如果导入不认（不同 Eclipse 版本对 launch 类型的校验不一致），退回方式一，两项配置效果完全一样。

## 调试时自动抓日志

`Run → Debug Configurations → 你的配置 → Main 页最下方`，把外部工具加进构建步骤不可行
（Eclipse 只支持 Build 步骤）。

替代做法：CubeIDE 里调试启动后，用 **Ctrl+Alt+Shift+B** 或 External Tools 工具栏手动点一下抓包，
10 秒的窗口足够看启动日志。

## 说明

Eclipse 的 Console 视图默认用 UTF-8（Window → Preferences → General → Workspace → Text file
encoding），抓到的数据是 ASCII，不会乱码。
