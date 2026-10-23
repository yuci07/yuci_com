# 参与 yuci_com

感谢愿意帮忙。这个项目很小，规则也很简单。

## 环境

- JDK 21 或更高
- 不需要 IDE，`javac` + 任何编辑器都行（源码是单文件）

```bash
# 编译
javac -encoding UTF-8 -d build/classes -cp lib/jSerialComm-2.11.4.jar src/YuciCom.java

# 跑自检（提交前必须全过）
java -cp "build/classes;lib/jSerialComm-2.11.4.jar" YuciCom --selftest
```

Windows 的 classpath 分隔符是 `;`，Linux / macOS 用 `:`。也可以直接用 `build.bat` 或 `./build.sh`。

## 提交前自检清单

- [ ] `--selftest` 全过（当前 33 项）
- [ ] 改了 CRC / HEX / 组包逻辑的，**必须补一条自检用例**
- [ ] 界面改动附一张 `docs/` 下的截图
- [ ] 没有引入新的第三方依赖（除非有充分理由，且要能离线打包）
- [ ] 源码保持 UTF-8、缩进 4 空格、不提交 IDE 配置文件

## 代码风格

- 单文件结构，内部类按功能分组：`Sum` / `Hex` / `Frame` / `SerialEngine` / `Config` / `MainFrame` / 各对话框
- 注释写「为什么」，不写「是什么」；踩过的坑一定要留注释，防止后来人再踩
- 中文注释、中文界面文案都可以，保持一致即可
- 新增用户可见的字符串请用中文，符合这个工具的目标用户

## 提交 PR

1. Fork 本仓库，从 `main` 拉分支：`feat/xxx` 或 `fix/xxx`
2. 一个 PR 只做一件事，标题写清楚改了什么
3. 描述里说明：动机、改动点、怎么验证的（截图 / 自检输出）
4. 等 CI 绿灯

## 想加功能但不确定？

先开 Issue 聊。串口工具的每个功能都要考虑「会不会让界面更乱」，
所以讨论清楚再动手，比写完被拒更省时间。

## 行为准则

对事不对人，新人提问不要嘲笑。这个项目的初衷就是让刚学单片机的人少走点弯路。
