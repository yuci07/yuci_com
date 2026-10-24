import com.fazecast.jSerialComm.SerialPort;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LoadState;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.PackageLib;
import org.luaj.vm2.lib.StringLib;
import org.luaj.vm2.lib.TableLib;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.ZeroArgFunction;
import org.luaj.vm2.lib.jse.JseBaseLib;
import org.luaj.vm2.lib.jse.JseMathLib;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * yuci_com —— 串口调试助手（Java Swing + jSerialComm 原生版）
 *
 * 不依赖浏览器、不起本地服务，直接通过 jSerialComm 操作 COM 口。
 * CLI:
 *   --list                                    列出串口
 *   --selftest                                跑 CRC / 组包 / 解析 / 编码自检
 *   --headless --port COM5 --baud 115200 --seconds 5 [--hex] [--out file]
 */
public class YuciCom {

    public static final String APP = "yuci_com";
    public static final String VERSION = "2.4";
    /** 开源仓库地址：fork 之后把这一行改成自己的仓库即可。 */
    public static final String PROJECT_URL = "https://github.com/yuci07/yuci_com";

    public static void main(String[] args) {
        List<String> a = Arrays.asList(args);
        if (a.contains("--list")) {
            System.setProperty("java.awt.headless", "true");
            listPorts();
            return;
        }
        if (a.contains("--selftest")) {
            System.setProperty("java.awt.headless", "true");
            int fail = SelfTest.run();
            System.exit(fail == 0 ? 0 : 1);
            return;
        }
        if (a.contains("--headless")) {
            System.setProperty("java.awt.headless", "true");
            System.exit(Headless.run(args));
            return;
        }
        Gui.start();
    }

    static void listPorts() {
        SerialPort[] ports = SerialPort.getCommPorts();
        if (ports.length == 0) {
            System.out.println("(no serial port found)");
            return;
        }
        for (SerialPort p : ports) {
            System.out.printf("%-8s %s%n", p.getSystemPortName(), p.getDescriptivePortName());
        }
    }

    /* ==================== 校验算法 ==================== */

    static final class Sum {
        static int sum8(byte[] d, int off, int len) {
            int s = 0;
            for (int i = off; i < off + len; i++) s = (s + (d[i] & 0xFF)) & 0xFF;
            return s;
        }

        static int crc8(byte[] d, int off, int len) {
            int crc = 0x00;
            for (int i = off; i < off + len; i++) {
                crc ^= (d[i] & 0xFF);
                for (int b = 0; b < 8; b++) {
                    crc = ((crc & 0x80) != 0) ? (((crc << 1) ^ 0x07) & 0xFF) : ((crc << 1) & 0xFF);
                }
            }
            return crc;
        }

        static int crc8Maxim(byte[] d, int off, int len) {
            int crc = 0x00;
            for (int i = off; i < off + len; i++) {
                crc ^= (d[i] & 0xFF);
                for (int b = 0; b < 8; b++) {
                    crc = ((crc & 0x01) != 0) ? ((crc >>> 1) ^ 0x8C) : (crc >>> 1);
                }
            }
            return crc & 0xFF;
        }

        static int crc16Modbus(byte[] d, int off, int len) {
            int crc = 0xFFFF;
            for (int i = off; i < off + len; i++) {
                crc ^= (d[i] & 0xFF);
                for (int b = 0; b < 8; b++) {
                    crc = ((crc & 0x0001) != 0) ? ((crc >>> 1) ^ 0xA001) : (crc >>> 1);
                }
            }
            return crc & 0xFFFF;
        }

        static int crc16Ccitt(byte[] d, int off, int len) {
            int crc = 0xFFFF;
            for (int i = off; i < off + len; i++) {
                crc ^= ((d[i] & 0xFF) << 8);
                for (int b = 0; b < 8; b++) {
                    crc = ((crc & 0x8000) != 0) ? (((crc << 1) ^ 0x1021) & 0xFFFF) : ((crc << 1) & 0xFFFF);
                }
            }
            return crc & 0xFFFF;
        }

        /** 按名称算校验，返回字节数组（小端/大端按算法惯例）。 */
        static byte[] apply(String kind, byte[] d, int off, int len) {
            switch (kind) {
                case "SUM8":
                    return new byte[]{(byte) sum8(d, off, len)};
                case "CRC8":
                    return new byte[]{(byte) crc8(d, off, len)};
                case "CRC8-MAXIM":
                    return new byte[]{(byte) crc8Maxim(d, off, len)};
                case "CRC16-MODBUS": {
                    int c = crc16Modbus(d, off, len);
                    return new byte[]{(byte) (c & 0xFF), (byte) ((c >>> 8) & 0xFF)};
                }
                case "CRC16-CCITT": {
                    int c = crc16Ccitt(d, off, len);
                    return new byte[]{(byte) ((c >>> 8) & 0xFF), (byte) (c & 0xFF)};
                }
                default:
                    return new byte[0];
            }
        }

        static final String[] KINDS = {"SUM8", "CRC8", "CRC8-MAXIM", "CRC16-MODBUS", "CRC16-CCITT"};
    }

    /* ==================== 十六进制工具 ==================== */

    static final class Hex {
        static byte[] parse(String s) {
            if (s == null) return new byte[0];
            String t = s.replaceAll("(?i)0x", "").replaceAll("[^0-9A-Fa-f]", "");
            if (t.isEmpty()) return new byte[0];
            if ((t.length() & 1) != 0) t = "0" + t;
            byte[] out = new byte[t.length() / 2];
            for (int i = 0; i < out.length; i++) {
                out[i] = (byte) Integer.parseInt(t.substring(i * 2, i * 2 + 2), 16);
            }
            return out;
        }

        static String of(byte[] d, int len) {
            StringBuilder sb = new StringBuilder(len * 3);
            for (int i = 0; i < len; i++) {
                sb.append(String.format("%02X", d[i] & 0xFF));
                if (i != len - 1) sb.append(' ');
            }
            return sb.toString();
        }

        static String of(byte[] d) {
            return of(d, d.length);
        }

        static byte[] concat(byte[]... parts) {
            int n = 0;
            for (byte[] p : parts) n += p.length;
            byte[] out = new byte[n];
            int at = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, out, at, p.length);
                at += p.length;
            }
            return out;
        }

        /** 接收区 HEX 显示：每 16 字节换行，列号跨批次延续，保证多次收到的小包也能对齐。 */
        static String dump(byte[] d, int[] col) {
            StringBuilder sb = new StringBuilder(d.length * 3 + 8);
            for (byte b : d) {
                if (col[0] == 0) sb.append("  ");
                sb.append(String.format("%02X ", b & 0xFF));
                if (++col[0] == 16) {
                    sb.append('\n');
                    col[0] = 0;
                }
            }
            return sb.toString();
        }
    }

    /* ==================== 协议帧 ==================== */

    static final class Frame {
        byte[] header = new byte[0];
        boolean withLen = false;
        byte[] payload = new byte[0];
        String check = "NONE";
        byte[] tail = new byte[0];

        byte[] build() {
            byte[] lenField = withLen ? new byte[]{(byte) (payload.length & 0xFF)} : new byte[0];
            byte[] body = Hex.concat(header, lenField, payload);
            byte[] ck = "NONE".equals(check) ? new byte[0] : Sum.apply(check, body, 0, body.length);
            return Hex.concat(body, ck, tail);
        }
    }

    /* ==================== 串口引擎 ==================== */

    interface SerialListener {
        void onData(byte[] data, int len);

        void onClosed(String reason);
    }

    static final class SerialEngine {
        private SerialPort port;
        private volatile boolean running;
        private Thread rxThread;
        private SerialListener listener;
        private final StringBuffer lastError = new StringBuffer();

        boolean isOpen() {
            return port != null && port.isOpen();
        }

        String lastError() {
            return lastError.toString();
        }

        String portName() {
            return port == null ? "" : port.getSystemPortName();
        }

        /* 已打开端口上驱动真正生效的参数。注意必须从打开的那个实例读，
           重新 getCommPort(name) 拿到的是新对象，返回的只是默认值（9600 8N1）。 */
        int actualBaud() {
            return port == null ? 0 : port.getBaudRate();
        }

        int actualDataBits() {
            return port == null ? 0 : port.getNumDataBits();
        }

        int actualParity() {
            return port == null ? SerialPort.NO_PARITY : port.getParity();
        }

        int actualStopBits() {
            return port == null ? SerialPort.ONE_STOP_BIT : port.getNumStopBits();
        }

        boolean open(String name, int baud, int dataBits, int parity, int stopBits, int flow,
                     int readTimeoutMs, SerialListener l) {
            close();
            lastError.setLength(0);
            SerialPort p = find(name);
            if (p == null) {
                lastError.append("找不到串口 ").append(name);
                return false;
            }
            p.setBaudRate(baud);
            p.setNumDataBits(dataBits);
            p.setParity(parity);
            p.setNumStopBits(stopBits);
            p.setFlowControl(flow);
            p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, readTimeoutMs, 0);
            if (!p.openPort()) {
                lastError.append("打开失败（错误码 ").append(p.getLastErrorCode())
                        .append("）；可能被其它程序占用");
                return false;
            }
            port = p;
            listener = l;
            running = true;
            /* 注意：这里必须用 SerialPort.readBytes()，不能用 getInputStream()。
               jSerialComm 的 InputStream 包装在 TIMEOUT_READ_SEMI_BLOCKING 模式下，
               超时（无数据）会抛 SerialPortTimeoutException 而不是返回 0，
               一旦总线空闲超过 readTimeoutMs 接收线程就死了（现象：打开后永远收不到数据）。
               readBytes() 在超时时正常返回 0，可以安全地当作「空闲」继续循环。 */
            rxThread = new Thread(() -> {
                byte[] buf = new byte[8192];
                final SerialPort sp = port;
                while (running) {
                    int n;
                    try {
                        n = sp.readBytes(buf, buf.length);
                    } catch (Exception e) {
                        if (running) {
                            lastError.append("读取异常: ").append(e.getMessage());
                            if (listener != null) listener.onClosed("读取中断：" + e.getMessage());
                        }
                        break;
                    }
                    if (n > 0) {
                        byte[] copy = new byte[n];
                        System.arraycopy(buf, 0, copy, 0, n);
                        if (listener != null) listener.onData(copy, n);
                    } else if (n < 0) {
                        if (!running) break;
                        if (listener != null) listener.onClosed("设备已断开");
                        break;
                    }
                    /* n == 0：读超时但端口正常，仅代表这段时间没有数据 */
                }
            }, "yuci-rx");
            rxThread.setDaemon(true);
            rxThread.start();
            return true;
        }

        void close() {
            running = false;
            if (rxThread != null) {
                rxThread.interrupt();
                rxThread = null;
            }
            if (port != null) {
                try {
                    port.closePort();
                } catch (Exception ignored) {
                }
                port = null;
            }
        }

        int write(byte[] data) {
            if (port == null || !port.isOpen()) return -1;
            int n = port.writeBytes(data, data.length);
            return n < 0 ? -1 : n;
        }

        void setDtr(boolean on) {
            if (port == null || !port.isOpen()) return;
            if (on) port.setDTR();
            else port.clearDTR();
        }

        void setRts(boolean on) {
            if (port == null || !port.isOpen()) return;
            if (on) port.setRTS();
            else port.clearRTS();
        }

        /** 发送 BREAK：保持 200 ms 后释放（本工具最常见的用法）。 */
        boolean sendBreak(int holdMs) {
            if (port == null || !port.isOpen()) return false;
            boolean ok = port.setBreak();
            new javax.swing.Timer(holdMs, e -> {
                try {
                    port.clearBreak();
                } catch (Exception ignored) {
                }
                ((javax.swing.Timer) e.getSource()).stop();
            }).start();
            return ok;
        }

        /** 清空驱动接收缓冲，丢弃还没读走的数据。 */
        void flushInput() {
            if (port == null || !port.isOpen()) return;
            try {
                byte[] junk = new byte[4096];
                int guard = 0;
                while (port.bytesAvailable() > 0 && guard++ < 64) {
                    int n = port.readBytes(junk, Math.min(junk.length, port.bytesAvailable()));
                    if (n <= 0) break;
                }
            } catch (Exception ignored) {
            }
        }

        boolean cts() {
            try {
                return port != null && port.isOpen() && port.getCTS();
            } catch (Exception e) {
                return false;
            }
        }

        boolean dsr() {
            try {
                return port != null && port.isOpen() && port.getDSR();
            } catch (Exception e) {
                return false;
            }
        }

        static SerialPort find(String name) {
            for (SerialPort p : SerialPort.getCommPorts()) {
                if (p.getSystemPortName().equalsIgnoreCase(name)) return p;
            }
            return null;
        }
    }

    /* ==================== 配置持久化 ==================== */

    static final class Config {
        private static final File DIR = new File(System.getProperty("user.home"), ".yuci_com");
        private static final File FILE = new File(DIR, "config.properties");
        private static final Properties P = new Properties();

        static {
            try {
                if (FILE.isFile()) {
                    try (InputStream in = new FileInputStream(FILE)) {
                        P.load(in);
                    }
                }
            } catch (Exception ignored) {
            }
        }

        static String get(String k, String def) {
            return P.getProperty(k, def);
        }

        static int getInt(String k, int def) {
            try {
                return Integer.parseInt(P.getProperty(k, String.valueOf(def)).trim());
            } catch (Exception e) {
                return def;
            }
        }

        static boolean getBool(String k, boolean def) {
            return Boolean.parseBoolean(P.getProperty(k, String.valueOf(def)));
        }

        static void put(String k, Object v) {
            P.setProperty(k, String.valueOf(v));
        }

        static void save() {
            try {
                if (!DIR.isDirectory() && !DIR.mkdirs()) return;
                try (OutputStream out = new FileOutputStream(FILE)) {
                    P.store(out, "yuci_com " + VERSION);
                }
            } catch (Exception ignored) {
            }
        }

        /** 把外部 properties 合并进来（用于「文件 -> 导入配置」）。 */
        static boolean loadFrom(File f) {
            if (f == null || !f.isFile()) return false;
            try (InputStream in = new FileInputStream(f)) {
                Properties tmp = new Properties();
                tmp.load(in);
                for (String k : tmp.stringPropertyNames()) {
                    P.setProperty(k, tmp.getProperty(k));
                }
                save();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        static boolean saveTo(File f) {
            try {
                try (OutputStream out = new FileOutputStream(f)) {
                    P.store(out, "yuci_com " + VERSION + " exported config");
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }

    /* ==================== 字符编码 ==================== */

    static final class Enc {
        /** 显示用的编码清单；第一项是默认值。 */
        static final String[] NAMES = {"UTF-8", "GBK", "GB18030", "GB2312", "ISO-8859-1", "US-ASCII"};

        static Charset of(String name) {
            try {
                return Charset.forName(name);
            } catch (Exception e) {
                return StandardCharsets.UTF_8;
            }
        }
    }

    /**
     * 流式解码器：串口数据会随机切包，UTF-8 汉字很容易被切成两半。
     * 这里把解不出来的尾部字节留到下一包，避免出现「」乱码。
     */
    static final class TextStream {
        private String charset = "";
        private CharsetDecoder dec;
        private byte[] carry = new byte[0];

        String charset() {
            return charset;
        }

        void setCharset(String name) {
            if (Objects.equals(name, charset)) return;
            charset = name;
            dec = Enc.of(name).newDecoder()
                    .onMalformedInput(CodingErrorAction.REPLACE)
                    .onUnmappableCharacter(CodingErrorAction.REPLACE);
            carry = new byte[0];
        }

        void reset() {
            carry = new byte[0];
            if (dec != null) dec.reset();
        }

        String decode(byte[] data) {
            if (dec == null) setCharset("UTF-8");
            int total = carry.length + data.length;
            if (total == 0) return "";
            ByteBuffer in = ByteBuffer.allocate(total);
            in.put(carry).put(data).flip();
            CharBuffer out = CharBuffer.allocate(total * 2 + 16);
            dec.reset();
            dec.decode(in, out, false);
            if (in.hasRemaining()) {
                byte[] c = new byte[in.remaining()];
                in.get(c);
                carry = c;
            } else {
                carry = new byte[0];
            }
            out.flip();
            return out.toString();
        }
    }

    /* ==================== 快捷发送（多页发送） ==================== */

    static final class QuickItem {
        String name = "";
        String data = "";
        boolean hex = false;
        int lineEnd = 1;   /* 与主界面「行尾」下拉一致：0 无 / 1 \r\n / 2 \n / 3 \r */

        QuickItem() {
        }

        QuickItem(String name, String data, boolean hex, int lineEnd) {
            this.name = name;
            this.data = data;
            this.hex = hex;
            this.lineEnd = lineEnd;
        }

        byte[] bytes() {
            byte[] body = hex ? Hex.parse(data) : data.getBytes(StandardCharsets.UTF_8);
            return Hex.concat(body, lineEndBytes(lineEnd));
        }

        String summary() {
            String s = data.replace("\r", "\\r").replace("\n", "\\n");
            if (s.length() > 46) s = s.substring(0, 46) + "…";
            return s;
        }

        QuickItem copy() {
            return new QuickItem(name, data, hex, lineEnd);
        }
    }

    static byte[] lineEndBytes(int idx) {
        switch (idx) {
            case 1:
                return new byte[]{'\r', '\n'};
            case 2:
                return new byte[]{'\n'};
            case 3:
                return new byte[]{'\r'};
            default:
                return new byte[0];
        }
    }

    static final class QuickStore {
        private static final File FILE = new File(new File(System.getProperty("user.home"), ".yuci_com"),
                "quicksend.properties");

        static List<QuickItem> load() {
            List<QuickItem> out = new ArrayList<>();
            if (!FILE.isFile()) return out;
            Properties p = new Properties();
            try (InputStream in = new FileInputStream(FILE)) {
                p.load(in);
            } catch (Exception e) {
                return out;
            }
            int n;
            try {
                n = Integer.parseInt(p.getProperty("count", "0").trim());
            } catch (Exception e) {
                n = 0;
            }
            for (int i = 0; i < n; i++) {
                QuickItem q = new QuickItem();
                q.name = p.getProperty(i + ".name", "指令 " + (i + 1));
                q.data = p.getProperty(i + ".data", "");
                q.hex = Boolean.parseBoolean(p.getProperty(i + ".hex", "false"));
                try {
                    q.lineEnd = Integer.parseInt(p.getProperty(i + ".end", "1").trim());
                } catch (Exception e) {
                    q.lineEnd = 1;
                }
                out.add(q);
            }
            return out;
        }

        static void save(List<QuickItem> items) {
            Properties p = new Properties();
            p.setProperty("count", String.valueOf(items.size()));
            for (int i = 0; i < items.size(); i++) {
                QuickItem q = items.get(i);
                p.setProperty(i + ".name", q.name);
                p.setProperty(i + ".data", q.data);
                p.setProperty(i + ".hex", String.valueOf(q.hex));
                p.setProperty(i + ".end", String.valueOf(q.lineEnd));
            }
            try {
                File dir = FILE.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) return;
                try (OutputStream out = new FileOutputStream(FILE)) {
                    p.store(out, "yuci_com quicksend");
                }
            } catch (Exception ignored) {
            }
        }
    }

    /* ==================== 自绘控件 ==================== */

    /** 带占位提示的多行文本框（JTextArea 原生没有 placeholder）。 */
    static final class HintTextArea extends JTextArea {
        private String hint;
        private final Color hintColor = new Color(0x94, 0x9A, 0xA6);

        HintTextArea(int rows, int cols, String hint) {
            super(rows, cols);
            this.hint = hint;
        }

        void setHint(String s) {
            if (!Objects.equals(s, hint)) {
                hint = s;
                repaint();
            }
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            if (getText().length() > 0 || hint == null || hint.isEmpty()) return;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font f = getFont();
            if (f == null || f.canDisplayUpTo(hint) >= 0) {
                f = new Font("Microsoft YaHei UI", Font.PLAIN, f == null ? 12 : f.getSize());
            }
            g.setFont(f);
            g.setColor(hintColor);
            Insets in = getInsets();
            g.drawString(hint, in.left + 2, in.top + g.getFontMetrics().getAscent());
            g.dispose();
        }
    }

    /**
     * 支持背景图片的接收区。
     * 背景按「可见区域」绘制而不是按文档高度绘制，所以滚动时背景不会跟着跑。
     * 图上再盖一层可调浓度的遮罩，保证任何图片下文字都看得清。
     */
    static final class BgTextPane extends JTextPane {
        static final String[] MODES = {"拉伸铺满", "等比适应", "居中", "平铺"};

        private BufferedImage src;
        private int mode = 0;
        private float mask = 0.55f;
        private Color maskColor = new Color(0x1E, 0x1E, 0x1E);

        private BufferedImage scaled;
        private int scaledW, scaledH;

        BgTextPane() {
            setOpaque(false);
        }

        void setImage(BufferedImage img) {
            src = img;
            scaled = null;
            repaint();
        }

        BufferedImage image() {
            return src;
        }

        boolean hasImage() {
            return src != null;
        }

        void setMode(int m) {
            if (m < 0 || m >= MODES.length || m == mode) return;
            mode = m;
            scaled = null;
            repaint();
        }

        int mode() {
            return mode;
        }

        void setMask(float a) {
            mask = Math.max(0f, Math.min(0.95f, a));
            repaint();
        }

        float mask() {
            return mask;
        }

        void setMaskColor(Color c) {
            maskColor = c;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            if (src == null) {
                super.paintComponent(g0);
                return;
            }
            Rectangle vis = getVisibleRect();
            if (vis.isEmpty()) {
                super.paintComponent(g0);
                return;
            }
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(getBackground());
            g.fillRect(vis.x, vis.y, vis.width, vis.height);

            switch (mode) {
                case 1: {
                    double s = Math.min(vis.getWidth() / src.getWidth(), vis.getHeight() / src.getHeight());
                    int w = Math.max(1, (int) Math.round(src.getWidth() * s));
                    int h = Math.max(1, (int) Math.round(src.getHeight() * s));
                    g.drawImage(fit(w, h), vis.x + (vis.width - w) / 2, vis.y + (vis.height - h) / 2, null);
                    break;
                }
                case 2:
                    g.drawImage(src, vis.x + (vis.width - src.getWidth()) / 2,
                            vis.y + (vis.height - src.getHeight()) / 2, null);
                    break;
                case 3:
                    for (int y = vis.y; y < vis.y + vis.height; y += src.getHeight()) {
                        for (int x = vis.x; x < vis.x + vis.width; x += src.getWidth()) {
                            g.drawImage(src, x, y, null);
                        }
                    }
                    break;
                default:
                    g.drawImage(fit(vis.width, vis.height), vis.x, vis.y, null);
            }

            int a = Math.round(mask * 255f);
            if (a > 0) {
                g.setColor(new Color(maskColor.getRed(), maskColor.getGreen(), maskColor.getBlue(), a));
                g.fillRect(vis.x, vis.y, vis.width, vis.height);
            }
            g.dispose();
            super.paintComponent(g0);
        }

        private BufferedImage fit(int w, int h) {
            if (scaled != null && scaledW == w && scaledH == h) return scaled;
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            scaled = img;
            scaledW = w;
            scaledH = h;
            return img;
        }

        /* 只有内容比视口宽时才停止拉伸：短内容时背景仍然铺满整个接收区，
           长行（HEX 16 列）时出现横向滚动条，列不会错位。 */
        @Override
        public boolean getScrollableTracksViewportWidth() {
            Container parent = getParent();
            if (parent == null) return true;
            return getUI().getPreferredSize(this).width <= parent.getWidth();
        }
    }

    /* ==================== 自检 ==================== */


    static final class SelfTest {
        static int pass, fail;

        static void eq(String name, Object got, Object want) {
            boolean ok = String.valueOf(got).equals(String.valueOf(want));
            if (ok) {
                pass++;
                System.out.printf("  [ OK ] %-34s %s%n", name, got);
            } else {
                fail++;
                System.out.printf("  [FAIL] %-34s got=%s want=%s%n", name, got, want);
            }
        }

        static int run() {
            System.out.println("yuci_com " + VERSION + " self test");
            System.out.println("-- CRC 标准向量 (\"123456789\") --");
            byte[] v = "123456789".getBytes(StandardCharsets.US_ASCII);
            eq("SUM8", String.format("%02X", Sum.sum8(v, 0, v.length)), "DD");
            eq("CRC8 (poly 0x07)", String.format("%02X", Sum.crc8(v, 0, v.length)), "F4");
            eq("CRC8-MAXIM", String.format("%02X", Sum.crc8Maxim(v, 0, v.length)), "A1");
            eq("CRC16-MODBUS", String.format("%04X", Sum.crc16Modbus(v, 0, v.length)), "4B37");
            eq("CRC16-CCITT-FALSE", String.format("%04X", Sum.crc16Ccitt(v, 0, v.length)), "29B1");

            System.out.println("-- 十六进制解析 --");
            eq("parse 'AA BB'", Hex.of(Hex.parse("AA BB")), "AA BB");
            eq("parse '0xaa,0xbb'", Hex.of(Hex.parse("0xaa,0xbb")), "AA BB");
            eq("parse 'AABBCC'", Hex.of(Hex.parse("AABBCC")), "AA BB CC");
            eq("parse 奇数位 'ABC'", Hex.of(Hex.parse("ABC")), "0A BC");
            eq("parse 空串", Hex.parse("").length, 0);

            System.out.println("-- HEX 显示换行（16 字节一列对齐）--");
            int[] col = {0};
            String s1 = Hex.dump(Hex.parse("00 01 02"), col);
            eq("3 字节不换行", s1.contains("\n"), false);
            eq("列计数 = 3", col[0], 3);
            String s2 = Hex.dump(new byte[13], col);
            eq("3+13 正好满 16 触发换行", s2.endsWith("\n"), true);
            eq("列计数归零", col[0], 0);
            String s3 = Hex.dump(new byte[17], col);
            eq("17 字节只换行 1 次", s3.chars().filter(ch -> ch == '\n').count(), 1L);
            eq("剩余列计数 = 1", col[0], 1);

            System.out.println("-- 协议组包 --");            Frame f = new Frame();
            f.header = Hex.parse("AA 55");
            f.withLen = true;
            f.payload = Hex.parse("01 02 03");
            f.check = "CRC16-MODBUS";
            f.tail = Hex.parse("0D 0A");
            byte[] out = f.build();
            eq("AA55 + len + 3 数据 + CRC16 + 0D0A", Hex.of(out), Hex.of(Hex.concat(
                    Hex.parse("AA 55 03 01 02 03"),
                    Sum.apply("CRC16-MODBUS", Hex.parse("AA 55 03 01 02 03"), 0, 6),
                    Hex.parse("0D 0A"))));
            eq("帧长", out.length, 6 + 2 + 2);

            Frame f2 = new Frame();
            f2.payload = Hex.parse("41 42");
            f2.check = "NONE";
            eq("无帧头/无校验 只发数据", Hex.of(f2.build()), "41 42");

            System.out.println("-- 字符编码（流式解码，跨包不断字）--");
            TextStream ts = new TextStream();
            ts.setCharset("UTF-8");
            byte[] cn = "中文测试".getBytes(StandardCharsets.UTF_8);
            String d1 = ts.decode(Arrays.copyOfRange(cn, 0, 4));
            String d2 = ts.decode(Arrays.copyOfRange(cn, 4, cn.length));
            eq("UTF-8 被切成 4+8 后拼接", d1 + d2, "中文测试");
            eq("首包不产生替换符", d1.contains("\uFFFD"), false);
            TextStream gs = new TextStream();
            gs.setCharset("GBK");
            byte[] gb = "中文测试".getBytes(Enc.of("GBK"));
            String g1 = gs.decode(Arrays.copyOfRange(gb, 0, 3));
            String g2 = gs.decode(Arrays.copyOfRange(gb, 3, gb.length));
            eq("GBK 被切成 3+5 后拼接", g1 + g2, "中文测试");
            TextStream as = new TextStream();
            as.setCharset("US-ASCII");
            eq("ASCII 直通", as.decode("AT+OK".getBytes(StandardCharsets.US_ASCII)), "AT+OK");

            System.out.println("-- 快捷发送 --");
            QuickItem qi = new QuickItem("t", "AA BB", true, 1);
            eq("HEX + CRLF", Hex.of(qi.bytes()), "AA BB 0D 0A");
            QuickItem qa = new QuickItem("t", "ok", false, 2);
            eq("ASCII + LF", Hex.of(qa.bytes()), "6F 6B 0A");
            eq("行尾 0 无", lineEndBytes(0).length, 0);
            eq("行尾 3 为 CR", lineEndBytes(3).length, 1);
            eq("摘要把换行转义", new QuickItem("t", "a\r\nb", false, 0).summary(), "a\\r\\nb");

            System.out.println("-- 字体 --");
            eq("中文等宽字体可显示汉字", SessionPanel.monoZh(13).canDisplay('中'), true);
            eq("中文等宽字体可显示 HEX", SessionPanel.monoZh(13).canDisplay('A'), true);
            eq("英文等宽字体为 Consolas",
                    "Consolas".equalsIgnoreCase(SessionPanel.monoEn(13).getFamily(Locale.ENGLISH)), true);

            System.out.println("-- 背景显示方式 --");
            eq("BgTextPane 模式数", BgTextPane.MODES.length, 4);

            System.out.println("-- 串口枚举 --");
            SerialPort[] ports = SerialPort.getCommPorts();
            System.out.println("  找到 " + ports.length + " 个串口");
            for (SerialPort p : ports) {
                System.out.println("    " + p.getSystemPortName() + "  " + p.getDescriptivePortName());
            }
            eq("jSerialComm 可用（原生库已加载）", ports.length >= 0, true);

            System.out.println();
            System.out.printf("结果: %d passed, %d failed%n", pass, fail);
            return fail;
        }
    }

    /* ==================== 无界面模式（命令行抓数据） ==================== */

    static final class Headless {
        static int run(String[] args) {
            Map<String, String> opt = new LinkedHashMap<>();
            for (int i = 0; i < args.length; i++) {
                if (args[i].startsWith("--")) {
                    String k = args[i].substring(2);
                    String v = (i + 1 < args.length && !args[i + 1].startsWith("--")) ? args[++i] : "true";
                    opt.put(k, v);
                }
            }
            String portName = opt.getOrDefault("port", "");
            if (portName.isEmpty()) {
                System.err.println("用法: --headless --port COM5 [--baud 115200] [--seconds 5] [--hex] [--out file]");
                return 2;
            }
            int baud = Integer.parseInt(opt.getOrDefault("baud", "115200"));
            int seconds = Integer.parseInt(opt.getOrDefault("seconds", "5"));
            boolean hex = opt.containsKey("hex");
            String outFile = opt.get("out");
            PrintStream out = System.out;
            FileOutputStream fos = null;
            try {
                if (outFile != null) {
                    fos = new FileOutputStream(outFile);
                    /* 一边写文件、一边打到控制台：IDE 里能实时看，事后又有日志可查 */
                    final PrintStream fileOut = new PrintStream(fos, true, "UTF-8");
                    final PrintStream console = System.out;
                    out = new PrintStream(new OutputStream() {
                        @Override
                        public void write(int b) throws java.io.IOException {
                            fileOut.write(b);
                            console.write(b);
                        }

                        @Override
                        public void write(byte[] b, int off, int len) throws java.io.IOException {
                            fileOut.write(b, off, len);
                            console.write(b, off, len);
                        }

                        @Override
                        public void flush() throws java.io.IOException {
                            fileOut.flush();
                            console.flush();
                        }
                    }, true, "UTF-8");
                }
            } catch (Exception e) {
                System.err.println("无法写文件: " + e.getMessage());
                return 2;
            }
            SerialEngine engine = new SerialEngine();
            final PrintStream o = out;
            final AtomicBoolean got = new AtomicBoolean(false);
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            boolean ok = engine.open(portName, baud, 8, SerialPort.NO_PARITY, SerialPort.ONE_STOP_BIT,
                    SerialPort.FLOW_CONTROL_DISABLED, 100, new SerialListener() {
                        @Override
                        public void onData(byte[] data, int len) {
                            got.set(true);
                            acc.write(data, 0, len);
                        }

                        @Override
                        public void onClosed(String reason) {
                            o.println("-- port closed: " + reason + " --");
                        }
                    });
            if (!ok) {
                System.err.println("打开失败: " + engine.lastError());
                return 3;
            }
            o.println("-- opened " + portName + " @" + baud + " 8N1 for "
                    + (seconds > 0 ? seconds + "s" : "as long as you want") + " --");
            long end = seconds > 0 ? System.currentTimeMillis() + seconds * 1000L : Long.MAX_VALUE;
            long lastDump = 0;
            while (System.currentTimeMillis() < end) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
                if (acc.size() > 0 && System.currentTimeMillis() - lastDump > 400) {
                    byte[] chunk = acc.toByteArray();
                    acc.reset();
                    lastDump = System.currentTimeMillis();
                    if (hex) {
                        o.println(Hex.of(chunk));
                    } else {
                        o.print(new String(chunk, StandardCharsets.UTF_8).replace("\r\n", "\n"));
                    }
                    o.flush();
                }
            }
            byte[] rest = acc.toByteArray();
            if (rest.length > 0) {
                if (hex) o.println(Hex.of(rest));
                else o.print(new String(rest, StandardCharsets.UTF_8));
            }
            engine.close();
            o.println();
            o.println("-- closed, received " + (got.get() ? "data" : "nothing") + " --");
            o.flush();
            if (fos != null) {
                try {
                    fos.close();
                } catch (Exception ignored) {
                }
            }
            return 0;
        }
    }

    /* ==================== 视觉规范 ==================== */

    /** 浅色主题的一整套色值和字体。想换配色只改这里。 */
    static final class Ui {
        static final Color BG = new Color(0xF5, 0xF6, 0xF8);
        static final Color SURFACE = new Color(0xFF, 0xFF, 0xFF);
        static final Color PANEL = new Color(0xFA, 0xFB, 0xFC);
        static final Color BORDER = new Color(0xE3, 0xE6, 0xEB);
        static final Color TEXT = new Color(0x1F, 0x23, 0x28);
        static final Color MUTED = new Color(0x6B, 0x72, 0x80);
        static final Color PRIMARY = new Color(0x2F, 0x6F, 0xED);
        static final Color PRIMARY_HOVER = new Color(0x25, 0x60, 0xD4);
        static final Color PRIMARY_DOWN = new Color(0x1D, 0x4E, 0xB3);
        static final Color HOVER = new Color(0xEE, 0xF2, 0xF8);
        static final Color OK = new Color(0x16, 0xA3, 0x4A);
        static final Color DANGER = new Color(0xDC, 0x2A, 0x2A);

        static Font font(int size) {
            return new Font("Microsoft YaHei UI", Font.PLAIN, size);
        }

        static Font bold(int size) {
            return new Font("Microsoft YaHei UI", Font.BOLD, size);
        }
    }

    /** 扁平按钮：primary 走实心强调色，其余是白底细描边；都有 hover / 按下态。 */
    static class FlatButton extends JButton {

        private final boolean primary;
        private boolean over, down;

        FlatButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setFont(Ui.font(12));
            setForeground(primary ? Color.WHITE : Ui.TEXT);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(new EmptyBorder(5, 14, 5, 14));
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    over = true;
                    repaint();
                }

                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    over = false;
                    down = false;
                    repaint();
                }

                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    if (isEnabled()) {
                        down = true;
                        repaint();
                    }
                }

                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                    down = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth(), h = getHeight();
            Color fill;
            if (!isEnabled()) {
                fill = primary ? new Color(0xC2, 0xD3, 0xF5) : new Color(0xF4, 0xF5, 0xF7);
            } else if (primary) {
                fill = down ? Ui.PRIMARY_DOWN : (over ? Ui.PRIMARY_HOVER : Ui.PRIMARY);
            } else {
                fill = down ? new Color(0xE1, 0xE7, 0xF1) : (over ? Ui.HOVER : Ui.SURFACE);
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 8, 8);
            if (!primary) {
                g2.setColor(isEnabled() ? Ui.BORDER : new Color(0xEE, 0xF0, 0xF3));
                g2.drawRoundRect(0, 0, w - 1, h - 1, 8, 8);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 圆角浅色分组面板，用来把一排控件圈成一块，别让它们糊成一片。 */
    static class GroupPanel extends JPanel {

        GroupPanel(LayoutManager lm) {
            super(lm);
            setOpaque(false);
            setBorder(new EmptyBorder(4, 10, 4, 10));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Ui.PANEL);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.setColor(Ui.BORDER);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 10, 10);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 扁平标签页：选中项白底 + 底部一条强调色，未选中透明灰字。 */
    static final class FlatTabbedPaneUI extends javax.swing.plaf.basic.BasicTabbedPaneUI {

        private final Color areaBg;

        FlatTabbedPaneUI(Color areaBg) {
            this.areaBg = areaBg;
        }

        @Override
        protected void installDefaults() {
            super.installDefaults();
            tabAreaInsets = new Insets(6, 10, 0, 10);
            tabInsets = new Insets(6, 14, 7, 14);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
            contentBorderInsets = new Insets(0, 0, 0, 0);
        }

        @Override
        protected void paintTabArea(Graphics g, int place, int sel) {
            Rectangle clip = g.getClipBounds();
            if (clip != null) {
                g.setColor(areaBg);
                g.fillRect(clip.x, clip.y, clip.width, clip.height);
            }
            super.paintTabArea(g, place, sel);
        }

        @Override
        protected void paintTabBackground(Graphics g, int place, int index,
                                          int x, int y, int w, int h, boolean selected) {
            if (!selected) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(new Color(0xEC, 0xF2, 0xFE));
            g2.fillRoundRect(x + 1, y + 2, w - 2, h - 3, 9, 9);
            g2.dispose();
        }

        @Override
        protected void paintTabBorder(Graphics g, int place, int index,
                                      int x, int y, int w, int h, boolean selected) {
            if (!selected) return;
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(Ui.PRIMARY);
            g2.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g2.drawLine(x + 11, y + h - 2, x + w - 11, y + h - 2);
            g2.dispose();
        }

        @Override
        protected void paintContentBorder(Graphics g, int place, int sel) {
        }

        @Override
        protected void paintFocusIndicator(Graphics g, int place, Rectangle[] rects,
                                           int index, Rectangle icon, Rectangle text, boolean sel) {
        }

        @Override
        protected void paintText(Graphics g, int place, Font font, FontMetrics m,
                                 int index, String text, Rectangle rect, boolean selected) {
            g.setColor(selected ? Ui.TEXT : Ui.MUTED);
            g.setFont(selected ? Ui.bold(12) : Ui.font(12));
            int x = rect.x + (rect.width - m.stringWidth(text)) / 2;
            int y = rect.y + (rect.height + m.getAscent() - m.getDescent()) / 2;
            g.drawString(text, x, y);
        }
    }

    /* ==================== 图形界面 ==================== */

    static final class Gui {
        static void start() {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                UIManager.put("TextField.font", new Font("Microsoft YaHei UI", Font.PLAIN, 12));
                UIManager.put("ComboBox.font", new Font("Microsoft YaHei UI", Font.PLAIN, 12));
                UIManager.put("Label.font", new Font("Microsoft YaHei UI", Font.PLAIN, 12));
                UIManager.put("CheckBox.font", new Font("Microsoft YaHei UI", Font.PLAIN, 12));
                UIManager.put("Button.font", new Font("Microsoft YaHei UI", Font.PLAIN, 12));
                UIManager.put("Menu.font", new Font("Microsoft YaHei UI", Font.PLAIN, 12));
                UIManager.put("MenuItem.font", new Font("Microsoft YaHei UI", Font.PLAIN, 12));
            } catch (Exception ignored) {
            }
            SwingUtilities.invokeLater(() -> new AppFrame().setVisible(true));
        }
    }

    static final class PortItem {
        final String name;
        final String desc;

        PortItem(String name, String desc) {
            this.name = name;
            this.desc = desc;
        }

        @Override
        public String toString() {
            return name + "  ·  " + desc;
        }
    }

    /** 一次接收事件，带到达时刻，用于「分包超时」自动断帧显示。 */
    static final class RxChunk {
        final byte[] data;
        final long t;

        RxChunk(byte[] data, long t) {
            this.data = data;
            this.t = t;
        }
    }

    static final class SessionPanel extends JPanel {

        /* --- 串口 --- */
        private final SerialEngine engine = new SerialEngine();
        private final JComboBox<PortItem> cbPort = new JComboBox<>();
        private final JComboBox<String> cbBaud = new JComboBox<>(new String[]{
                "1200", "2400", "4800", "9600", "19200", "38400", "57600",
                "115200", "230400", "460800", "921600", "1500000", "3000000"});
        private final JComboBox<String> cbData = new JComboBox<>(new String[]{"5", "6", "7", "8"});
        private final JComboBox<String> cbParity = new JComboBox<>(new String[]{"无 None", "奇 Odd", "偶 Even", "标记 Mark", "空格 Space"});
        private final JComboBox<String> cbStop = new JComboBox<>(new String[]{"1", "1.5", "2"});
        private final JComboBox<String> cbFlow = new JComboBox<>(new String[]{"无", "RTS/CTS 硬件", "XON/XOFF 软件", "DSR/DTR"});
        private final FlatButton btnOpen = new FlatButton("打开串口", true);
        private final FlatButton btnRefresh = new FlatButton("刷新", false);
        private final JCheckBox chkDtr = new JCheckBox("DTR", true);
        private final JCheckBox chkRts = new JCheckBox("RTS", true);

        /* --- 接收区 --- */
        private final BgTextPane rxPane = new BgTextPane();
        private final StyledDocument rxDoc = rxPane.getStyledDocument();
        private final JScrollPane rxScroll = new JScrollPane(rxPane);
        private final JCheckBox chkHexView = new JCheckBox("HEX 显示");
        private final JCheckBox chkTimestamp = new JCheckBox("时间戳");
        private final JCheckBox chkPause = new JCheckBox("暂停显示");
        private final JCheckBox chkAutoScroll = new JCheckBox("自动滚动", true);
        private final JComboBox<String> cbEncoding = new JComboBox<>(Enc.NAMES);
        private final JComboBox<String> cbGap = new JComboBox<>(new String[]{
                "不断帧", "5 ms", "10 ms", "20 ms", "50 ms", "100 ms", "200 ms"});
        private final FlatButton btnClear = new FlatButton("清空", false);
        private final FlatButton btnSaveLog = new FlatButton("保存日志", false);
        private final FlatButton btnBg = new FlatButton("背景…", false);

        /* --- 发送区 --- */
        private final HintTextArea txArea = new HintTextArea(4, 40, "请输入文本（Ctrl+Enter 发送）");
        private final JCheckBox chkHexSend = new JCheckBox("HEX 发送");
        private final JComboBox<String> cbLineEnd = new JComboBox<>(new String[]{"无", "\\r\\n", "\\n", "\\r"});
        private final FlatButton btnSend = new FlatButton("发送", true);
        private final JCheckBox chkTimed = new JCheckBox("定时发送");
        private final JTextField tfInterval = new JTextField("1000", 6);
        private final JTextField tfCount = new JTextField("0", 4);
        private final FlatButton btnFile = new FlatButton("发送文件…", false);
        private final FlatButton btnFrame = new FlatButton("协议组包…", false);
        private final FlatButton btnCrc = new FlatButton("CRC 计算…", false);
        private final JProgressBar progress = new JProgressBar(0, 100);

        /* --- 状态栏 --- */
        private final JLabel lblStatus = new JLabel("未连接");
        private final JLabel lblSignals = new JLabel("CTS -   DSR -");
        private final JLabel lblCount = new JLabel("RX 0 B   TX 0 B");
        private final JLabel lblRate = new JLabel("0 B/s");

        private final SimpleAttributeSet stRx = new SimpleAttributeSet();
        private final SimpleAttributeSet stTx = new SimpleAttributeSet();
        private final SimpleAttributeSet stSys = new SimpleAttributeSet();
        private final SimpleAttributeSet stErr = new SimpleAttributeSet();

        private final ConcurrentLinkedQueue<RxChunk> rxQueue = new ConcurrentLinkedQueue<>();
        private final ArrayDeque<String> history = new ArrayDeque<>();
        private final ArrayDeque<String> bgRecent = new ArrayDeque<>();
        private final TextStream rxDec = new TextStream();
        private long rxBytes, txBytes, rxChunks, txFrames;
        private long openAt, lastRxTick, rateMarkBytes, rateMarkAt;
        private final int[] hexCol = {0};   /* 用数组包一层，便于传给 Hex.dump 累计列号 */
        private boolean stickToBottom = true;
        private Timer flushTimer, timedSendTimer, signalTimer, rateTimer;
        private int timedSent;
        private int fontSize = 13;
        private String fontFamily = "zh";
        private boolean closingPortProgrammatically;
        private boolean fullScreen;
        private File lastDir = new File(System.getProperty("user.home"));
        private File bgFile;
        private QuickSendPanel quickPanel;
        private JSplitPane mainSplit;
        private JTabbedPane sendTabs;
        private JMenu bgRecentMenu;

        /* --- 收发记录（导出 CSV / Excel 与画图共用） --- */
        private static final int REC_MAX = 200_000;
        private final ArrayDeque<Rec> recs = new ArrayDeque<>();
        private ChartFrame chart;

        private final AppFrame host;
        private final JMenuBar myMenu;
        private final int no;

        SessionPanel(AppFrame host, int no) {
            this.host = host;
            this.no = no;
            setLayout(new BorderLayout());

            initStyles();
            buildUi();
            myMenu = buildMenu();
            loadConfig();
            refreshPorts();

            flushTimer = new Timer(40, e -> flushRx());
            flushTimer.start();

            signalTimer = new Timer(600, e -> updateSignals());
            signalTimer.start();

            rateTimer = new Timer(1000, e -> updateRate());
            rateTimer.start();

            restoreBackground();

            sys("yuci_com " + VERSION + " 就绪。选好串口点「打开串口」。");
        }

        JMenuBar menuBar() {
            return myMenu;
        }

        /* ---------- 样式与皮肤 ---------- */

        private void initStyles() {
            setBackground(Ui.BG);
            StyleConstants.setForeground(stRx, new Color(0xE6, 0xE6, 0xE6));
            StyleConstants.setForeground(stTx, new Color(0x6C, 0xB6, 0xF0));
            StyleConstants.setForeground(stSys, new Color(0x9E, 0xA6, 0xB2));
            StyleConstants.setForeground(stErr, new Color(0xF0, 0x7A, 0x7A));
            StyleConstants.setItalic(stSys, true);

            rxPane.setEditable(false);
            rxPane.setBackground(new Color(0x1E, 0x1E, 0x1E));
            rxPane.setForeground(new Color(0xE6, 0xE6, 0xE6));
            rxPane.setCaretColor(new Color(0xE6, 0xE6, 0xE6));
            rxPane.setFont(mono(fontSize));
            rxPane.setBorder(new EmptyBorder(6, 8, 6, 8));
            rxScroll.getViewport().setBackground(new Color(0x1E, 0x1E, 0x1E));
            rxScroll.setBackground(new Color(0x1E, 0x1E, 0x1E));
            rxScroll.setBorder(new EmptyBorder(0, 0, 0, 0));

            rxScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
                JScrollBar sb = rxScroll.getVerticalScrollBar();
                stickToBottom = (sb.getValue() + sb.getVisibleAmount() >= sb.getMaximum() - 24);
            });

            txArea.setFont(mono(fontSize));
            txArea.setLineWrap(true);
            txArea.setWrapStyleWord(false);

            progress.setStringPainted(true);
            progress.setVisible(false);
            progress.setPreferredSize(new Dimension(160, 18));

            btnOpen.setPreferredSize(new Dimension(112, 32));
            btnOpen.setFont(Ui.bold(12));
            btnSend.setPreferredSize(new Dimension(96, 34));
            btnSend.setFont(Ui.bold(13));

            chkHexView.setSelected(Config.getBool("view.hex", false));
            chkTimestamp.setSelected(Config.getBool("view.ts", true));
            chkAutoScroll.setSelected(Config.getBool("view.scroll", true));
            chkHexSend.setSelected(Config.getBool("send.hex", false));
            updateTxHint();
        }

        private void updateTxHint() {
            txArea.setHint(chkHexSend.isSelected()
                    ? "请输入 HEX，例如 AA 55 01 02（Ctrl+Enter 发送）"
                    : "请输入文本（Ctrl+Enter 发送）");
        }

        /* 默认等宽字体：必须是「中英文都能显示」的等宽字体。
           物理字体缺字时 Java2D 不会自动 fallback（Consolas 画中文就是方块），
           所以候选里只放能显示中文的等宽字体，找不到才退回逻辑字体 Monospaced。 */
        static Font mono(int size) {
            return monoZh(size);
        }

        static Font monoZh(int size) {
            String[] cands = {"Sarasa Mono SC", "NSimSun", "Microsoft YaHei Mono", "Monospaced"};
            for (String n : cands) {
                Font f = new Font(n, Font.PLAIN, size);
                boolean exists = n.equalsIgnoreCase(f.getFamily(Locale.ENGLISH)) || n.equals(f.getFamily());
                if (exists && f.canDisplay('中')) return f;
            }
            return new Font(Font.MONOSPACED, Font.PLAIN, size);
        }

        /** 英文优先的等宽字体（HEX 显示最漂亮，但中文会变方块）。 */
        static Font monoEn(int size) {
            Font f = new Font("Consolas", Font.PLAIN, size);
            if ("Consolas".equalsIgnoreCase(f.getFamily(Locale.ENGLISH))) return f;
            return new Font(Font.MONOSPACED, Font.PLAIN, size);
        }

        private Font currentFont() {
            switch (fontFamily) {
                case "en":
                    return monoEn(fontSize);
                case "ui":
                    return new Font("Microsoft YaHei UI", Font.PLAIN, fontSize);
                default:
                    return monoZh(fontSize);
            }
        }

        private void refreshFonts() {
            Font f = currentFont();
            rxPane.setFont(f);
            txArea.setFont(f);
            Config.put("view.fontFamily", fontFamily);
            Config.put("view.font", fontSize);
        }

        private void applyFontFamily(String kind) {
            fontFamily = kind;
            refreshFonts();
            String name = "en".equals(kind) ? "Consolas（英文优先）"
                    : "ui".equals(kind) ? "微软雅黑（界面字体）" : "新宋体等宽（中文优先）";
            sys("显示字体已切换为 " + name);
        }

        /* ---------- 界面组装 ---------- */

        private void buildUi() {
            setLayout(new BorderLayout());

            JPanel north = new JPanel(new BorderLayout());
            north.add(buildPortBar(), BorderLayout.NORTH);
            north.add(buildRxToolbar(), BorderLayout.SOUTH);
            add(north, BorderLayout.NORTH);

            JPanel send = buildSendPanel();
            send.setMinimumSize(new Dimension(120, 130));

            mainSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, buildCenter(), send);
            mainSplit.setResizeWeight(1.0);
            mainSplit.setDividerSize(6);
            mainSplit.setBorder(null);
            add(mainSplit, BorderLayout.CENTER);

            wireActions();
            SwingUtilities.invokeLater(() -> mainSplit.setDividerLocation(0.66));
        }

        private JComponent buildPortBar() {
            JPanel wrap = new JPanel(new BorderLayout(0, 9));
            wrap.setBackground(Ui.SURFACE);
            wrap.setBorder(new EmptyBorder(10, 12, 10, 12));

            /* 第一行：选口 + 主操作 + 控制线 */
            JPanel r1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            r1.setOpaque(false);
            r1.add(tag("串口"));
            cbPort.setPreferredSize(new Dimension(310, 30));
            cbPort.setFont(Ui.font(12));
            r1.add(cbPort);
            btnRefresh.setPreferredSize(new Dimension(68, 32));
            r1.add(btnRefresh);
            r1.add(btnOpen);
            r1.add(vline());
            r1.add(chkDtr);
            r1.add(chkRts);
            wrap.add(r1, BorderLayout.NORTH);

            /* 第二行：串口参数，圈成一块浅色圆角，两段用竖线隔开 */
            JPanel p1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
            p1.setOpaque(false);
            cbBaud.setEditable(true);
            p1.add(tag("波特率"));
            combo(cbBaud, 96);
            p1.add(cbBaud);
            p1.add(tag("数据位"));
            combo(cbData, 56);
            p1.add(cbData);
            p1.add(tag("校验"));
            combo(cbParity, 96);
            p1.add(cbParity);
            p1.add(tag("停止位"));
            combo(cbStop, 60);
            p1.add(cbStop);
            p1.add(tag("流控"));
            combo(cbFlow, 120);
            p1.add(cbFlow);

            JPanel p2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 7, 0));
            p2.setOpaque(false);
            p2.add(tag("编码"));
            combo(cbEncoding, 96);
            p2.add(cbEncoding);
            p2.add(tag("断帧"));
            combo(cbGap, 88);
            cbGap.setToolTipText("超过该时间没有新数据，就认为一帧结束并换行显示");
            p2.add(cbGap);

            JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
            row.setOpaque(false);
            row.add(p1);
            row.add(vline());
            row.add(p2);

            GroupPanel gp = new GroupPanel(new BorderLayout());
            gp.setBorder(new EmptyBorder(5, 6, 5, 6));
            gp.add(row, BorderLayout.WEST);
            wrap.add(gp, BorderLayout.CENTER);
            return wrap;
        }

        private JLabel tag(String s) {
            JLabel l = new JLabel(s);
            l.setFont(Ui.font(12));
            l.setForeground(Ui.MUTED);
            return l;
        }

        /** 状态点：返回 HTML 开头（调用方自己补 </html>）。 */
        private static String dot(String color) {
            return "<html><font color='" + color + "'>&#9679;</font>&nbsp; ";
        }

        private JComponent vline() {
            JPanel p = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    g.setColor(Ui.BORDER);
                    g.fillRect(getWidth() / 2, 3, 1, Math.max(0, getHeight() - 6));
                }
            };
            p.setOpaque(false);
            p.setPreferredSize(new Dimension(9, 20));
            return p;
        }

        private void combo(JComboBox<?> cb, int w) {
            cb.setPreferredSize(new Dimension(w, 30));
            cb.setFont(Ui.font(12));
            cb.setBackground(Ui.SURFACE);
        }

        private JPanel buildRxToolbar() {
            JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
            p.setBackground(Ui.SURFACE);
            p.setBorder(new MatteBorderTop());
            p.add(chkHexView);
            p.add(chkTimestamp);
            p.add(chkPause);
            p.add(chkAutoScroll);
            p.add(vline());
            btnClear.setPreferredSize(new Dimension(64, 30));
            btnSaveLog.setPreferredSize(new Dimension(88, 30));
            btnBg.setPreferredSize(new Dimension(74, 30));
            p.add(btnClear);
            p.add(btnSaveLog);
            p.add(btnBg);
            return p;
        }

        private JComponent buildCenter() {
            rxPane.setCaretPosition(0);
            return rxScroll;
        }

        private JPanel buildSendPanel() {
            JPanel outer = new JPanel(new BorderLayout(0, 0));
            outer.setBorder(new MatteBorderTop());

            /* --- 手工发送页 --- */
            JPanel manual = new JPanel(new BorderLayout(0, 0));

            JPanel bar = new JPanel(new BorderLayout());
            bar.setBackground(Ui.SURFACE);
            bar.setBorder(new EmptyBorder(6, 12, 6, 12));

            JPanel leftBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            leftBar.setOpaque(false);
            leftBar.add(chkHexSend);
            leftBar.add(tag("行尾"));
            combo(cbLineEnd, 82);
            leftBar.add(cbLineEnd);
            leftBar.add(vline());
            leftBar.add(chkTimed);
            leftBar.add(tag("间隔"));
            tfInterval.setPreferredSize(new Dimension(64, 28));
            tfInterval.setFont(Ui.font(12));
            tfInterval.setToolTipText("定时发送的间隔，单位毫秒");
            leftBar.add(tfInterval);
            leftBar.add(tag("次"));
            tfCount.setPreferredSize(new Dimension(48, 28));
            tfCount.setFont(Ui.font(12));
            tfCount.setToolTipText("发送次数，0 表示无限循环");
            leftBar.add(tfCount);

            JPanel rightBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            rightBar.setOpaque(false);
            btnFile.setPreferredSize(new Dimension(96, 30));
            btnFrame.setPreferredSize(new Dimension(96, 30));
            btnCrc.setPreferredSize(new Dimension(94, 30));
            rightBar.add(btnFile);
            rightBar.add(btnFrame);
            rightBar.add(btnCrc);
            rightBar.add(btnSend);
            rightBar.add(progress);

            bar.add(leftBar, BorderLayout.WEST);
            bar.add(rightBar, BorderLayout.EAST);

            JScrollPane sp = new JScrollPane(txArea);
            sp.setPreferredSize(new Dimension(100, 82));
            sp.setBorder(new EmptyBorder(0, 0, 0, 0));

            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setBackground(Ui.SURFACE);
            bottom.setBorder(new EmptyBorder(2, 12, 8, 12));
            bottom.add(sp, BorderLayout.CENTER);

            manual.add(bar, BorderLayout.NORTH);
            manual.add(bottom, BorderLayout.CENTER);

            /* --- 快捷发送页 --- */
            quickPanel = new QuickSendPanel(this);
            JPanel quick = new JPanel(new BorderLayout());
            quick.setBorder(new EmptyBorder(4, 10, 2, 10));
            quick.add(quickPanel, BorderLayout.CENTER);

            sendTabs = new JTabbedPane(JTabbedPane.TOP, JTabbedPane.SCROLL_TAB_LAYOUT);
            sendTabs.addTab("手工发送", manual);
            sendTabs.addTab("快捷发送", quick);
            sendTabs.setUI(new FlatTabbedPaneUI(Ui.SURFACE));
            sendTabs.setBackground(Ui.SURFACE);
            manual.setBackground(Ui.SURFACE);
            quick.setBackground(Ui.SURFACE);
            sendTabs.setPreferredSize(new Dimension(100, 196));
            sendTabs.setMinimumSize(new Dimension(100, 150));

            outer.add(sendTabs, BorderLayout.CENTER);
            outer.add(buildStatusBar(), BorderLayout.SOUTH);
            return outer;
        }

        private JPanel buildStatusBar() {
            JPanel p = new JPanel(new BorderLayout());
            p.setBackground(Ui.SURFACE);
            p.setBorder(new javax.swing.border.CompoundBorder(
                    new MatteBorderTop(), new EmptyBorder(5, 12, 5, 12)));

            lblStatus.setFont(Ui.font(12));
            lblStatus.setForeground(Ui.MUTED);
            lblStatus.setText(dot("#9CA3AF") + "未连接</html>");
            p.add(lblStatus, BorderLayout.WEST);

            JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
            right.setOpaque(false);
            lblRate.setFont(Ui.font(12));
            lblRate.setForeground(Ui.MUTED);
            lblSignals.setFont(Ui.font(12));
            lblSignals.setForeground(Ui.MUTED);
            lblCount.setFont(Ui.font(12));
            lblCount.setForeground(Ui.TEXT);
            right.add(lblRate);
            right.add(vline());
            right.add(lblSignals);
            right.add(vline());
            right.add(lblCount);
            p.add(right, BorderLayout.EAST);
            return p;
        }

        private JMenuBar buildMenu() {
            JMenuBar mb = new JMenuBar();
            mb.setBackground(Ui.SURFACE);
            mb.setBorder(new EmptyBorder(5, 8, 5, 8));

            /* ---------------- 文件 ---------------- */
            JMenu mFile = new JMenu("文件");
            mFile.setMnemonic(KeyEvent.VK_F);
            mFile.add(item("新建标签页", e -> {
                if (host != null) host.addSession();
            }, ks(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK)));
            mFile.add(item("关闭当前标签页", e -> {
                if (host != null) host.closeSession(SessionPanel.this);
            }, ks(KeyEvent.VK_W, InputEvent.CTRL_DOWN_MASK)));
            mFile.add(item("新建会话（清空当前）", e -> newSession(),
                    ks(KeyEvent.VK_N, InputEvent.CTRL_DOWN_MASK)));
            mFile.addSeparator();
            mFile.add(item("打开接收日志…", e -> openLog(),
                    ks(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
            mFile.add(item("保存接收日志…", e -> saveLog(), ks(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK)));
            mFile.add(item("导出接收数据 → CSV…", e -> exportData(false), null));
            mFile.add(item("导出接收数据 → Excel…", e -> exportData(true), null));
            mFile.add(item("发送文件…", e -> sendFile(), null));
            mFile.addSeparator();
            mFile.add(item("导入配置…", e -> importConfig(), null));
            mFile.add(item("导出配置…", e -> exportConfig(), null));
            mFile.addSeparator();
            mFile.add(item("退出", e -> quit(), ks(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK)));
            mb.add(mFile);

            /* ---------------- 编辑 ---------------- */
            JMenu mEdit = new JMenu("编辑");
            mEdit.setMnemonic(KeyEvent.VK_E);
            mEdit.add(item("撤销", e -> txArea.requestFocus(), ks(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK)));
            mEdit.addSeparator();
            mEdit.add(item("剪切", e -> txArea.cut(), ks(KeyEvent.VK_X, InputEvent.CTRL_DOWN_MASK)));
            mEdit.add(item("复制", e -> txArea.copy(), ks(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK)));
            mEdit.add(item("粘贴", e -> txArea.paste(), ks(KeyEvent.VK_V, InputEvent.CTRL_DOWN_MASK)));
            mEdit.add(item("全选发送框", e -> txArea.selectAll(), ks(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK)));
            mEdit.addSeparator();
            mEdit.add(item("清空接收区", e -> clearRx(), ks(KeyEvent.VK_L, InputEvent.CTRL_DOWN_MASK)));
            mEdit.add(item("清空发送框", e -> txArea.setText(""), null));
            mEdit.add(item("清空发送历史", e -> {
                history.clear();
                sys("发送历史已清空");
            }, null));
            mb.add(mEdit);

            /* ---------------- 串口 ---------------- */
            JMenu mPort = new JMenu("串口");
            mPort.setMnemonic(KeyEvent.VK_S);
            mPort.add(item("刷新端口列表", e -> refreshPorts(), ks(KeyEvent.VK_F5, 0)));
            mPort.add(item("打开 / 关闭串口", e -> togglePort(), ks(KeyEvent.VK_O, InputEvent.CTRL_DOWN_MASK)));
            mPort.add(item("重新打开串口", e -> reopenPort(), ks(KeyEvent.VK_R, InputEvent.CTRL_DOWN_MASK)));
            mPort.addSeparator();

            JMenu mBaud = new JMenu("波特率");
            for (String b : new String[]{"9600", "19200", "38400", "57600", "115200",
                    "230400", "460800", "921600", "1500000", "3000000"}) {
                mBaud.add(item(b, e -> applyPortChoice("baud", b)));
            }
            mPort.add(mBaud);

            JMenu mData = new JMenu("数据位");
            for (String b : new String[]{"5", "6", "7", "8"}) {
                mData.add(item(b, e -> applyPortChoice("data", b)));
            }
            mPort.add(mData);

            JMenu mParity = new JMenu("校验");
            String[] pn = {"无 None", "奇 Odd", "偶 Even", "标记 Mark", "空格 Space"};
            for (String s : pn) mParity.add(item(s, e -> applyPortChoice("parity", s)));
            mPort.add(mParity);

            JMenu mStop = new JMenu("停止位");
            for (String s : new String[]{"1", "1.5", "2"}) {
                mStop.add(item(s, e -> applyPortChoice("stop", s)));
            }
            mPort.add(mStop);

            JMenu mFlow = new JMenu("流控");
            String[] fn = {"无", "RTS/CTS 硬件", "XON/XOFF 软件", "DSR/DTR"};
            for (String s : fn) mFlow.add(item(s, e -> applyPortChoice("flow", s)));
            mPort.add(mFlow);
            mPort.addSeparator();

            mPort.add(item("DTR 置位", e -> {
                chkDtr.setSelected(true);
                engine.setDtr(true);
            }, null));
            mPort.add(item("DTR 清零", e -> {
                chkDtr.setSelected(false);
                engine.setDtr(false);
            }, null));
            mPort.add(item("RTS 置位", e -> {
                chkRts.setSelected(true);
                engine.setRts(true);
            }, null));
            mPort.add(item("RTS 清零", e -> {
                chkRts.setSelected(false);
                engine.setRts(false);
            }, null));
            mPort.addSeparator();
            mPort.add(item("发送 BREAK (200 ms)", e -> {
                if (engine.sendBreak(200)) sys("已发送 BREAK，持续 200 ms");
                else err("串口未打开，无法发送 BREAK");
            }, null));
            mPort.add(item("丢弃接收缓冲", e -> {
                engine.flushInput();
                sys("已丢弃驱动接收缓冲中的残留数据");
            }, null));
            mb.add(mPort);

            /* ---------------- 工具 ---------------- */
            JMenu mTool = new JMenu("工具");
            mTool.setMnemonic(KeyEvent.VK_T);
            mTool.add(item("CRC / 校验计算…", e -> openCrcDialog(), ks(KeyEvent.VK_K, InputEvent.CTRL_DOWN_MASK)));
            mTool.add(item("协议组包…", e -> openFrameDialog(), ks(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK)));
            mTool.addSeparator();
            mTool.add(item("手工发送", e -> selectSendTab(0), ks(KeyEvent.VK_1, InputEvent.CTRL_DOWN_MASK)));
            mTool.add(item("快捷发送", e -> selectSendTab(1), ks(KeyEvent.VK_2, InputEvent.CTRL_DOWN_MASK)));
            mTool.addSeparator();
            mTool.add(item("数据统计…", e -> openStats(),
                    ks(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
            mTool.add(item("数据曲线图…", e -> openChart(),
                    ks(KeyEvent.VK_G, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
            mTool.add(item("脚本发送…", e -> openScript(),
                    ks(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));

            JMenu mEnc = new JMenu("字符编码");
            for (String s : Enc.NAMES) mEnc.add(item(s, e -> cbEncoding.setSelectedItem(s)));
            mTool.add(mEnc);

            JMenu mGap = new JMenu("接收断帧");
            for (int i = 0; i < cbGap.getItemCount(); i++) {
                final int idx = i;
                mGap.add(item(cbGap.getItemAt(i), e -> cbGap.setSelectedIndex(idx)));
            }
            mTool.add(mGap);
            mb.add(mTool);

            /* ---------------- 视图 ---------------- */
            JMenu mView = new JMenu("视图");
            mView.setMnemonic(KeyEvent.VK_V);
            mView.add(item("HEX 显示", e -> chkHexView.doClick(), ks(KeyEvent.VK_H, InputEvent.CTRL_DOWN_MASK)));
            mView.add(item("时间戳", e -> chkTimestamp.doClick(),
                    ks(KeyEvent.VK_T, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK)));
            mView.add(item("暂停显示", e -> chkPause.doClick(), ks(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK)));
            mView.add(item("自动滚动", e -> chkAutoScroll.doClick(), null));
            mView.addSeparator();
            JMenu mType = new JMenu("字体");
            mType.add(item("新宋体等宽（中文优先）", e -> applyFontFamily("zh"), null));
            mType.add(item("Consolas（英文优先）", e -> applyFontFamily("en"), null));
            mType.add(item("微软雅黑（界面字体）", e -> applyFontFamily("ui"), null));
            mView.add(mType);

            JMenu mFont = new JMenu("字号");
            for (int s : new int[]{11, 12, 13, 14, 16, 18, 20, 24}) {
                mFont.add(item(s + " px", e -> setFontSize(s)));
            }
            mView.add(mFont);
            mView.add(item("放大字号  Ctrl+滚轮↑", e -> setFontSize(Math.min(32, fontSize + 1)), null));
            mView.add(item("缩小字号  Ctrl+滚轮↓", e -> setFontSize(Math.max(9, fontSize - 1)), null));
            mView.addSeparator();
            mView.add(item("全屏切换", e -> toggleFullScreen(), ks(KeyEvent.VK_F11, 0)));
            mView.add(item("恢复默认布局", e -> resetLayout(), null));
            mb.add(mView);

            /* ---------------- 背景 ---------------- */
            JMenu mBg = new JMenu("背景");
            mBg.setMnemonic(KeyEvent.VK_B);
            mBg.add(item("选择图片…", e -> chooseBackground(), ks(KeyEvent.VK_B, InputEvent.CTRL_DOWN_MASK)));
            mBg.add(item("从剪贴板粘贴图片", e -> pasteBackground(), null));
            bgRecentMenu = new JMenu("最近使用");
            mBg.add(bgRecentMenu);
            mBg.add(item("清除背景", e -> clearBackground(), null));
            mBg.addSeparator();

            JMenu mBgMode = new JMenu("显示方式");
            for (int i = 0; i < BgTextPane.MODES.length; i++) {
                final int m = i;
                mBgMode.add(item(BgTextPane.MODES[i], e -> applyBgMode(m)));
            }
            mBg.add(mBgMode);

            JMenu mBgMask = new JMenu("遮罩浓度");
            int[] masks = {0, 20, 40, 55, 70, 85};
            for (int v : masks) {
                final int m = v;
                mBgMask.add(item(v + " %" + (v == 55 ? "（默认）" : ""), e -> applyBgMask(m)));
            }
            mBg.add(mBgMask);
            mBg.addSeparator();
            mBg.add(item("不透明度说明…", e -> JOptionPane.showMessageDialog(this,
                    "遮罩浓度越高，背景图越暗、文字越清楚。\n"
                            + "纯色或深色图片可以用 20~40%，花哨照片建议 55~70%，\n"
                            + "接近 85% 时基本只剩一层淡影。", "背景遮罩",
                    JOptionPane.INFORMATION_MESSAGE), null));
            mBg.addMenuListener(new javax.swing.event.MenuListener() {
                @Override
                public void menuSelected(javax.swing.event.MenuEvent e) {
                    rebuildBgRecentMenu();
                }

                @Override
                public void menuDeselected(javax.swing.event.MenuEvent e) {
                }

                @Override
                public void menuCanceled(javax.swing.event.MenuEvent e) {
                }
            });
            mb.add(mBg);

            /* ---------------- 帮助 ---------------- */
            JMenu mHelp = new JMenu("帮助");
            mHelp.setMnemonic(KeyEvent.VK_H);
            mHelp.add(item("快捷键", e -> showShortcuts(), ks(KeyEvent.VK_F1, 0)));
            mHelp.add(item("快速上手", e -> showHelp(), null));
            mHelp.addSeparator();
            mHelp.add(item("关于 " + APP, e -> showAbout(), null));
            mHelp.add(item("项目主页（GitHub）", e -> openProjectPage(), null));
            mb.add(mHelp);
            for (int i = 0; i < mb.getMenuCount(); i++) {
                JMenu m = mb.getMenu(i);
                if (m != null) m.setFont(Ui.font(12));
            }
            return mb;
        }

        private static KeyStroke ks(int key, int mods) {
            return KeyStroke.getKeyStroke(key, mods);
        }

        private JMenuItem item(String text, java.awt.event.ActionListener l, KeyStroke accel) {
            JMenuItem mi = new JMenuItem(text);
            if (accel != null) mi.setAccelerator(accel);
            if (l != null) mi.addActionListener(l);
            return mi;
        }

        private JMenuItem item(String text, java.awt.event.ActionListener l) {
            return item(text, l, null);
        }

        /* ---------- 事件绑定 ---------- */

        private void wireActions() {
            btnRefresh.addActionListener(e -> refreshPorts());
            btnOpen.addActionListener(e -> togglePort());
            btnClear.addActionListener(e -> clearRx());
            btnSaveLog.addActionListener(e -> saveLog());
            btnBg.addActionListener(e -> chooseBackground());
            btnSend.addActionListener(e -> doSend());
            btnFile.addActionListener(e -> sendFile());
            btnFrame.addActionListener(e -> openFrameDialog());
            btnCrc.addActionListener(e -> openCrcDialog());
            chkDtr.addActionListener(e -> engine.setDtr(chkDtr.isSelected()));
            chkRts.addActionListener(e -> engine.setRts(chkRts.isSelected()));
            chkHexView.addActionListener(e -> {
                hexCol[0] = 0;
                Config.put("view.hex", chkHexView.isSelected());
            });
            chkTimestamp.addActionListener(e -> Config.put("view.ts", chkTimestamp.isSelected()));
            chkAutoScroll.addActionListener(e -> Config.put("view.scroll", chkAutoScroll.isSelected()));
            chkHexSend.addActionListener(e -> {
                updateTxHint();
                Config.put("send.hex", chkHexSend.isSelected());
            });
            cbEncoding.addActionListener(e -> {
                String cs = (String) cbEncoding.getSelectedItem();
                if (cs == null) return;
                rxDec.setCharset(cs);
                Config.put("view.charset", cs);
                if (isVisible()) sys("接收编码已切换为 " + cs);
            });
            cbGap.addActionListener(e -> Config.put("view.gap", cbGap.getSelectedIndex()));
            chkTimed.addActionListener(e -> {
                if (chkTimed.isSelected()) startTimedSend();
                else stopTimedSend();
            });

            KeyStroke send = KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);
            txArea.getInputMap().put(send, "yuci.send");
            txArea.getActionMap().put("yuci.send", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    doSend();
                }
            });
            KeyStroke up = KeyStroke.getKeyStroke(KeyEvent.VK_UP, InputEvent.CTRL_DOWN_MASK);
            txArea.getInputMap().put(up, "yuci.histUp");
            txArea.getActionMap().put("yuci.histUp", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    recallHistory();
                }
            });

            /* Ctrl + 滚轮调字号（鼠标停在接收区或发送区都生效） */
            java.awt.event.MouseWheelListener zoom = e -> {
                if (!e.isControlDown()) return;
                e.consume();
                setFontSize(fontSize + (e.getWheelRotation() < 0 ? 1 : -1));
            };
            rxPane.addMouseWheelListener(zoom);
            txArea.addMouseWheelListener(zoom);
        }

        /* ---------- 端口 ---------- */

        private void refreshPorts() {
            String keep = selectedPortName();
            SerialPort[] ports = SerialPort.getCommPorts();
            cbPort.removeAllItems();
            for (SerialPort p : ports) {
                cbPort.addItem(new PortItem(p.getSystemPortName(), p.getDescriptivePortName()));
            }
            if (keep != null) {
                for (int i = 0; i < cbPort.getItemCount(); i++) {
                    if (cbPort.getItemAt(i).name.equalsIgnoreCase(keep)) {
                        cbPort.setSelectedIndex(i);
                        break;
                    }
                }
            }
            if (engine.isOpen()) {
                boolean still = false;
                for (SerialPort p : ports) {
                    if (p.getSystemPortName().equalsIgnoreCase(engine.portName())) still = true;
                }
                if (!still) {
                    engine.close();
                    afterClose("串口被拔出");
                    sys("串口 " + engine.portName() + " 已断开（设备移除）");
                }
            }
            if (cbPort.getItemCount() == 0) {
                lblStatus.setText(dot("#9CA3AF") + "未发现串口，请插上 USB-TTL 后按 F5</html>");
            }
        }

        private String selectedPortName() {
            PortItem it = (PortItem) cbPort.getSelectedItem();
            return it == null ? null : it.name;
        }

        private void togglePort() {
            if (engine.isOpen()) {
                closingPortProgrammatically = true;
                engine.close();
                closingPortProgrammatically = false;
                afterClose("手动关闭");
                sys("串口已关闭");
                return;
            }
            String name = selectedPortName();
            if (name == null) {
                JOptionPane.showMessageDialog(this, "没有可用串口。请插上 USB-TTL 后点「刷新」。",
                        APP, JOptionPane.WARNING_MESSAGE);
                return;
            }
            int baud;
            try {
                baud = Integer.parseInt(String.valueOf(cbBaud.getEditor().getItem()).trim());
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "波特率不是合法数字", APP, JOptionPane.ERROR_MESSAGE);
                return;
            }
            int dataBits = Integer.parseInt((String) cbData.getSelectedItem());
            int parity = new int[]{SerialPort.NO_PARITY, SerialPort.ODD_PARITY, SerialPort.EVEN_PARITY,
                    SerialPort.MARK_PARITY, SerialPort.SPACE_PARITY}[cbParity.getSelectedIndex()];
            int stop = new int[]{SerialPort.ONE_STOP_BIT, SerialPort.ONE_POINT_FIVE_STOP_BITS,
                    SerialPort.TWO_STOP_BITS}[cbStop.getSelectedIndex()];
            int flow = new int[]{
                    SerialPort.FLOW_CONTROL_DISABLED,
                    SerialPort.FLOW_CONTROL_RTS_ENABLED | SerialPort.FLOW_CONTROL_CTS_ENABLED,
                    SerialPort.FLOW_CONTROL_XONXOFF_IN_ENABLED | SerialPort.FLOW_CONTROL_XONXOFF_OUT_ENABLED,
                    SerialPort.FLOW_CONTROL_DSR_ENABLED | SerialPort.FLOW_CONTROL_DTR_ENABLED
            }[cbFlow.getSelectedIndex()];

            boolean ok = engine.open(name, baud, dataBits, parity, stop, flow, 100, new SerialListener() {
                @Override
                public void onData(byte[] data, int len) {
                    rxQueue.add(new RxChunk(data, System.nanoTime()));
                }

                @Override
                public void onClosed(String reason) {
                    SwingUtilities.invokeLater(() -> {
                        if (closingPortProgrammatically) return;
                        afterClose(reason);
                        err(reason);
                    });
                }
            });
            if (!ok) {
                JOptionPane.showMessageDialog(this, "打开 " + name + " 失败\n\n" + engine.lastError(),
                        APP, JOptionPane.ERROR_MESSAGE);
                err("打开失败: " + engine.lastError());
                return;
            }
            engine.setDtr(chkDtr.isSelected());
            engine.setRts(chkRts.isSelected());
            afterOpen(name, baud, dataBits, parity, stop, flow);
            sys("已打开 " + name + " → 实际参数 " + engine.actualBaud() + " " + engine.actualDataBits()
                    + parityLetter(engine.actualParity()) + stopLetter(engine.actualStopBits())
                    + "，DTR=" + (chkDtr.isSelected() ? 1 : 0) + " RTS=" + (chkRts.isSelected() ? 1 : 0));
        }

        private void afterOpen(String name, int baud, int dataBits, int parity, int stop, int flow) {
            btnOpen.setText("关闭串口");
            cbPort.setEnabled(false);
            btnRefresh.setEnabled(false);
            cbBaud.setEnabled(false);
            cbData.setEnabled(false);
            cbParity.setEnabled(false);
            cbStop.setEnabled(false);
            cbFlow.setEnabled(false);
            openAt = System.currentTimeMillis();
            lblStatus.setText(dot("#16A34A") + "已连接 " + name + "  ·  " + baud + " "
                    + dataBits + parityLetter(parity) + stopLetter(stop) + "</html>");
            Config.put("port.name", name);
            Config.put("port.baud", baud);
            Config.put("port.data", dataBits);
            Config.put("port.parity", cbParity.getSelectedIndex());
            Config.put("port.stop", cbStop.getSelectedIndex());
            Config.put("port.flow", flow);
            if (host != null) host.retitle(this);
        }

        private void afterClose(String reason) {
            btnOpen.setText("打开串口");
            cbPort.setEnabled(true);
            btnRefresh.setEnabled(true);
            cbBaud.setEnabled(true);
            cbData.setEnabled(true);
            cbParity.setEnabled(true);
            cbStop.setEnabled(true);
            cbFlow.setEnabled(true);
            boolean abnormal = reason != null && !reason.isEmpty();
            lblStatus.setText(dot(abnormal ? "#DC2A2A" : "#9CA3AF") + "未连接"
                    + (abnormal ? "  ·  " + reason : "") + "</html>");
            openAt = 0;
            lastRxTick = 0;
            stopTimedSend();
            chkTimed.setSelected(false);
            if (host != null) host.retitle(this);
        }

        private void updateSignals() {
            if (!engine.isOpen()) {
                lblSignals.setText("CTS -   DSR -");
                return;
            }
            lblSignals.setText("CTS " + (engine.cts() ? "1" : "0") + "   DSR " + (engine.dsr() ? "1" : "0"));
        }

        /* ---------- 接收显示 ---------- */

        private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        /** 「断帧」下拉对应的毫秒值，0 表示不分包。 */
        private int gapMillis() {
            int[] v = {0, 5, 10, 20, 50, 100, 200};
            int i = cbGap.getSelectedIndex();
            return (i < 0 || i >= v.length) ? 0 : v[i];
        }

        private void flushRx() {
            if (rxQueue.isEmpty()) return;

            List<RxChunk> chunks = new ArrayList<>();
            RxChunk k;
            while ((k = rxQueue.poll()) != null) chunks.add(k);
            if (chunks.isEmpty()) return;

            long gapNs = gapMillis() * 1_000_000L;

            /* 按「到达间隔超过阈值」切成若干段，每段单独格式化 */
            List<byte[]> segs = new ArrayList<>();
            ByteArrayOutputStream cur = new ByteArrayOutputStream();
            long curT = 0, prevT = 0;
            boolean have = false;
            for (RxChunk x : chunks) {
                boolean newSeg = !have || (gapNs > 0 && x.t - prevT > gapNs);
                if (newSeg) {
                    if (have) {
                        segs.add(cur.toByteArray());
                        cur = new ByteArrayOutputStream();
                    }
                    curT = x.t;
                    have = true;
                }
                cur.write(x.data, 0, x.data.length);
                prevT = x.t;
                rxBytes += x.data.length;
                rxChunks++;
            }
            if (have) segs.add(cur.toByteArray());

            boolean crossTickGap = gapNs > 0 && lastRxTick > 0 && chunks.get(0).t - lastRxTick > gapNs;
            lastRxTick = prevT;

            updateCounters();
            if (chkPause.isSelected()) return;

            StringBuilder sb = new StringBuilder();
            String ts = chkTimestamp.isSelected()
                    ? "[" + LocalTime.now().format(TS_FMT) + "] " : "";
            for (int i = 0; i < segs.size(); i++) {
                if (i > 0 || crossTickGap) sb.append('\n');
                if (chkTimestamp.isSelected()) sb.append(ts);
                byte[] d = segs.get(i);
                String plain;
                if (chkHexView.isSelected()) {
                    sb.append(Hex.dump(d, hexCol));
                    plain = Hex.of(d);
                } else {
                    plain = rxDec.decode(d).replace("\r\n", "\n").replace('\r', '\n');
                    sb.append(plain);
                }
                record(false, System.currentTimeMillis(), d);
                /* HEX 显示时喂进去的是一串十六进制，画图会误判，所以只在文本模式喂 */
                if (chart != null && !chkHexView.isSelected()) chart.feed(plain);
            }
            append(sb.toString(), stRx);
        }

        private void append(String text, SimpleAttributeSet style) {
            if (text.isEmpty()) return;
            try {
                int len = rxDoc.getLength();
                if (len > 400_000) {
                    rxDoc.remove(0, len - 320_000);
                    hexCol[0] = 0;
                }
                rxDoc.insertString(rxDoc.getLength(), text, style);
            } catch (BadLocationException ignored) {
            }
            if (chkAutoScroll.isSelected() && stickToBottom) {
                rxPane.setCaretPosition(rxDoc.getLength());
            }
        }

        private void sys(String msg) {
            append("[系统] " + msg + "\n", stSys);
        }

        private void err(String msg) {
            append("[错误] " + msg + "\n", stErr);
        }

        private void clearRx() {
            try {
                rxDoc.remove(0, rxDoc.getLength());
            } catch (BadLocationException ignored) {
            }
            rxBytes = 0;
            txBytes = 0;
            hexCol[0] = 0;
            updateCounters();
        }

        private void updateCounters() {
            lblCount.setText("RX " + human(rxBytes) + "   TX " + human(txBytes));
        }

        static String human(long n) {
            if (n < 1024) return n + " B";
            if (n < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", n / 1024.0);
            return String.format(Locale.ROOT, "%.2f MB", n / 1048576.0);
        }

        private void saveLog() {
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setSelectedFile(new File("yuci_com_"
                    + java.time.LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".log"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            lastDir = f.getParentFile();
            try {
                String text = rxDoc.getText(0, rxDoc.getLength());
                Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
                sys("日志已保存: " + f.getAbsolutePath());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "保存失败: " + e.getMessage(), APP, JOptionPane.ERROR_MESSAGE);
            }
        }

        /* ---------- 收发记录 / 导出 ---------- */

        private void record(boolean tx, long t, byte[] d) {
            synchronized (recs) {
                recs.addLast(new Rec(t, tx, d));
                while (recs.size() > REC_MAX) recs.removeFirst();
            }
        }

        private static final DateTimeFormatter EXP_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

        private void exportData(boolean excel) {
            List<Rec> snap;
            synchronized (recs) {
                snap = new ArrayList<>(recs);
            }
            if (snap.isEmpty()) {
                sys("还没有可导出的数据 —— 先收发一些再来");
                return;
            }
            JFileChooser fc = new JFileChooser(lastDir);
            String stamp = java.time.LocalDateTime.now()
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            fc.setSelectedFile(new File("yuci_com_" + stamp + (excel ? ".xlsx" : ".csv")));
            fc.setFileFilter(excel
                    ? new javax.swing.filechooser.FileNameExtensionFilter("Excel 工作簿 (*.xlsx)", "xlsx")
                    : new javax.swing.filechooser.FileNameExtensionFilter("CSV 表格 (*.csv)", "csv"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            lastDir = f.getParentFile();
            String lower = f.getName().toLowerCase(Locale.ROOT);
            if (excel && !lower.endsWith(".xlsx")) f = new File(f.getPath() + ".xlsx");
            if (!excel && !lower.endsWith(".csv")) f = new File(f.getPath() + ".csv");

            Charset cs = Enc.of((String) cbEncoding.getSelectedItem());
            List<String[]> rows = new ArrayList<>(snap.size() + 1);
            rows.add(new String[]{"时间", "方向", "字节", "文本", "HEX"});
            for (Rec r : snap) {
                rows.add(new String[]{
                        EXP_FMT.format(java.time.Instant.ofEpochMilli(r.t)
                                .atZone(java.time.ZoneId.systemDefault())),
                        r.tx ? "TX" : "RX",
                        Integer.toString(r.data.length),
                        readable(r.data, cs),
                        Hex.of(r.data)});
            }
            try {
                if (excel) Xlsx.write(f, "收发记录", rows);
                else Files.write(f.toPath(), csvBytes(rows));
                sys("已导出 " + snap.size() + " 条记录到 " + f.getAbsolutePath());
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "导出失败: " + e.getMessage(),
                        APP, JOptionPane.ERROR_MESSAGE);
            }
        }

        /** 把不可见控制字符转成字面量，免得表格里一片乱码。 */
        static String readable(byte[] d, Charset cs) {
            String s = new String(d, cs);
            StringBuilder b = new StringBuilder(s.length() + 8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '\r') b.append("\\r");
                else if (c == '\n') b.append("\\n");
                else if (c == '\t') b.append("\\t");
                else if (c < 0x20 || c == 0x7f) b.append('.');
                else b.append(c);
            }
            return b.toString();
        }

        static byte[] csvBytes(List<String[]> rows) {
            StringBuilder sb = new StringBuilder();
            sb.append('\uFEFF');   /* BOM：Excel 打开中文才不乱码 */
            for (String[] row : rows) {
                for (int i = 0; i < row.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(csvField(row[i]));
                }
                sb.append("\r\n");
            }
            return sb.toString().getBytes(StandardCharsets.UTF_8);
        }

        static String csvField(String s) {
            if (s == null) return "";
            if (s.indexOf(',') < 0 && s.indexOf('"') < 0
                    && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) return s;
            return '"' + s.replace("\"", "\"\"") + '"';
        }

        private void openChart() {
            if (chart == null || !chart.isDisplayable()) {
                chart = new ChartFrame(SwingUtilities.getWindowAncestor(this));
                chart.setVisible(true);
            } else {
                chart.setExtendedState(chart.getExtendedState() & ~java.awt.Frame.ICONIFIED);
                chart.toFront();
            }
        }

        /* ---------- 脚本 ---------- */

        boolean isPortOpen() {
            return engine.isOpen();
        }

        /** 脚本里的 send：沿用界面上的行尾和编码设置。 */
        void sendScript(String text, boolean forceLine) {
            if (!engine.isOpen() || text == null) return;
            String t = text;
            if (forceLine && !t.endsWith("\n")) t = t + "\r\n";
            sendBytes(buildTxBytes(t), t);
        }

        void sendScriptHex(String hex) {
            if (!engine.isOpen() || hex == null) return;
            byte[] b = Hex.parse(hex);
            if (b.length > 0) sendBytes(b, Hex.of(b));
        }

        private void openScript() {
            new ScriptFrame(this).setVisible(true);
        }

        /* ---------- 发送 ---------- */

        private byte[] buildTxBytes(String text) {
            byte[] body = chkHexSend.isSelected()
                    ? Hex.parse(text)
                    : text.getBytes(Enc.of((String) cbEncoding.getSelectedItem()));
            return Hex.concat(body, lineEndBytes(cbLineEnd.getSelectedIndex()));
        }

        private void doSend() {
            String text = txArea.getSelectedText() != null ? txArea.getSelectedText() : txArea.getText();
            if (text == null || text.isEmpty()) {
                txArea.requestFocus();
                return;
            }
            sendBytes(buildTxBytes(text), text);
        }

        private void sendBytes(byte[] data, String echoText) {
            if (data.length == 0) return;
            if (!engine.isOpen()) {
                err("串口未打开，无法发送");
                return;
            }
            int n = engine.write(data);
            if (n < 0) {
                err("发送失败（" + engine.lastError() + "）");
                return;
            }
            txBytes += data.length;
            txFrames++;
            updateCounters();
            record(true, System.currentTimeMillis(), data);
            rememberHistory(echoText);
            StringBuilder sb = new StringBuilder();
            if (chkTimestamp.isSelected()) {
                sb.append('[').append(LocalTime.now().format(TS_FMT)).append("] ");
            }
            sb.append("[TX] ");
            if (chkHexSend.isSelected()) {
                sb.append(Hex.of(data));
            } else {
                sb.append(new String(data, Enc.of((String) cbEncoding.getSelectedItem()))
                        .replace("\r\n", "\\r\\n").replace("\n", "\\n"));
            }
            sb.append('\n');
            append(sb.toString(), stTx);
        }

        private void rememberHistory(String text) {
            if (text == null) return;
            String t = text.trim();
            if (t.isEmpty()) return;
            if (!history.isEmpty() && history.peekFirst().equals(t)) return;
            history.push(t);
            while (history.size() > 30) history.removeLast();
        }

        private void recallHistory() {
            if (history.isEmpty()) return;
            String t = history.removeLast();
            history.push(t);
            txArea.setText(t);
            txArea.requestFocus();
        }

        private void startTimedSend() {
            int ms;
            try {
                ms = Math.max(20, Integer.parseInt(tfInterval.getText().trim()));
            } catch (Exception e) {
                err("定时间隔不是合法数字");
                chkTimed.setSelected(false);
                return;
            }
            final int limit;
            try {
                limit = Math.max(0, Integer.parseInt(tfCount.getText().trim()));
            } catch (Exception e) {
                err("次数不是合法数字");
                chkTimed.setSelected(false);
                return;
            }
            timedSent = 0;
            if (timedSendTimer != null) timedSendTimer.stop();
            timedSendTimer = new Timer(ms, e -> {
                doSend();
                timedSent++;
                if (limit > 0 && timedSent >= limit) {
                    chkTimed.setSelected(false);
                    stopTimedSend();
                    sys("定时发送完成，共 " + timedSent + " 次");
                }
            });
            timedSendTimer.start();
            sys("定时发送启动，间隔 " + ms + " ms" + (limit > 0 ? "，共 " + limit + " 次" : "，无限循环"));
        }

        private void stopTimedSend() {
            if (timedSendTimer != null) {
                timedSendTimer.stop();
                timedSendTimer = null;
            }
        }

        private void sendFile() {
            if (!engine.isOpen()) {
                JOptionPane.showMessageDialog(this, "先打开串口。", APP, JOptionPane.WARNING_MESSAGE);
                return;
            }
            FileSendDialog.show(this, engine, bytes -> {
                txBytes += bytes;
                updateCounters();
            });
        }

        /* ---------- 会话 / 配置导入导出 ---------- */

        private void quit() {
            if (host != null) host.shutdown();
        }

        /** 关掉本会话占用的串口和定时器，从 Tab 里移除时调用。 */
        void closeSession() {
            if (flushTimer != null) flushTimer.stop();
            if (signalTimer != null) signalTimer.stop();
            if (rateTimer != null) rateTimer.stop();
            if (timedSendTimer != null) timedSendTimer.stop();
            saveConfig();
            engine.close();
        }

        /** 标签页标题：连上了显示「COM5 ·  115200」，没连就显示会话序号。 */
        String titleText() {
            if (!engine.isOpen()) return "会话 " + no;
            Object sel = cbPort.getSelectedItem();
            String name = sel instanceof PortItem ? ((PortItem) sel).name : "串口";
            return name + " ·  " + cbBaud.getSelectedItem();
        }

        /** 最后一个标签页不真的关掉，关串口 + 清干净就地复用。 */
        void closePortAndClear() {
            engine.close();
            clearRx();
            history.clear();
            sys("已关闭串口并清空。最后一个标签页保留，直接关窗口就退出。");
            if (host != null) host.retitle(this);
        }

        private void newSession() {
            clearRx();
            history.clear();
            lastRxTick = 0;
            sys("已开始新的会话（要同时看另一个串口请用「新建标签页」）");
        }

        private void openLog() {
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "日志 / 文本 (*.log, *.txt)", "log", "txt"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            lastDir = f.getParentFile();
            try {
                String text = Files.readString(f.toPath(), StandardCharsets.UTF_8);
                if (text.length() > 2_000_000) text = text.substring(text.length() - 2_000_000);
                append(text + (text.endsWith("\n") ? "" : "\n"), stSys);
                sys("已载入 " + f.getName() + "，仅回显查看，不会发到串口");
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "读取失败: " + e.getMessage(), APP, JOptionPane.ERROR_MESSAGE);
            }
        }

        private void importConfig() {
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("配置文件 (*.properties)", "properties"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            if (Config.loadFrom(f)) {
                loadConfig();
                sys("配置已导入: " + f.getName());
            } else {
                err("配置导入失败，文件格式可能不对");
            }
        }

        private void exportConfig() {
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setSelectedFile(new File("yuci_com_config.properties"));
            if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            saveConfig();
            if (Config.saveTo(f)) sys("配置已导出: " + f.getAbsolutePath());
            else err("配置导出失败");
        }

        /* ---------- 串口参数快捷设置 ---------- */

        private void reopenPort() {
            if (!engine.isOpen()) {
                togglePort();
                return;
            }
            String name = engine.portName();
            closingPortProgrammatically = true;
            engine.close();
            closingPortProgrammatically = false;
            afterClose("正在重新打开…");
            SwingUtilities.invokeLater(() -> {
                for (int i = 0; i < cbPort.getItemCount(); i++) {
                    if (cbPort.getItemAt(i).name.equalsIgnoreCase(name)) {
                        cbPort.setSelectedIndex(i);
                        break;
                    }
                }
                togglePort();
            });
        }

        private void applyPortChoice(String kind, String value) {
            switch (kind) {
                case "baud":
                    ((JTextField) cbBaud.getEditor().getEditorComponent()).setText(value);
                    break;
                case "data":
                    cbData.setSelectedItem(value);
                    break;
                case "parity":
                    cbParity.setSelectedItem(value);
                    break;
                case "stop":
                    cbStop.setSelectedItem(value);
                    break;
                case "flow":
                    cbFlow.setSelectedItem(value);
                    break;
                default:
                    break;
            }
            if (engine.isOpen()) sys("参数已改，点「重新打开串口」(Ctrl+R) 让它生效");
        }

        /* ---------- 视图 ---------- */

        private void setFontSize(int s) {
            fontSize = Math.max(9, Math.min(32, s));
            refreshFonts();
        }

        private void toggleFullScreen() {
            fullScreen = !fullScreen;
            applyWindowState();
        }

        /** 全屏状态是窗口的，不是面板的，得转给外层窗口。 */
        private void applyWindowState() {
            Window w = SwingUtilities.getWindowAncestor(this);
            if (!(w instanceof java.awt.Frame)) return;
            java.awt.Frame fr = (java.awt.Frame) w;
            fr.setExtendedState(fullScreen
                    ? (fr.getExtendedState() | java.awt.Frame.MAXIMIZED_BOTH)
                    : (fr.getExtendedState() & ~java.awt.Frame.MAXIMIZED_BOTH));
        }

        private void resetLayout() {
            fullScreen = false;
            applyWindowState();
            mainSplit.setDividerLocation(0.66);
            setFontSize(13);
            sendTabs.setSelectedIndex(0);
            sys("布局已恢复默认");
        }

        private void selectSendTab(int i) {
            if (sendTabs != null && i >= 0 && i < sendTabs.getTabCount()) sendTabs.setSelectedIndex(i);
        }

        private void updateRate() {
            long now = System.nanoTime();
            if (rateMarkAt == 0) {
                rateMarkAt = now;
                rateMarkBytes = rxBytes;
                return;
            }
            double sec = (now - rateMarkAt) / 1e9;
            long bps = sec > 0 ? Math.round((rxBytes - rateMarkBytes) / sec) : 0;
            rateMarkAt = now;
            rateMarkBytes = rxBytes;
            lblRate.setText(human(bps) + "/s");
        }

        /* ---------- 接收区背景 ---------- */

        private void chooseBackground() {
            JFileChooser fc = new JFileChooser(lastDir);
            fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(
                    "图片 (*.png, *.jpg, *.jpeg, *.bmp, *.gif)", "png", "jpg", "jpeg", "bmp", "gif"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
            File f = fc.getSelectedFile();
            lastDir = f.getParentFile();
            setBackground(f);
        }

        private void setBackground(File f) {
            try {
                BufferedImage img = ImageIO.read(f);
                if (img == null) {
                    err("这个文件不是可识别的图片: " + f.getName());
                    return;
                }
                bgFile = f;
                rxPane.setImage(img);
                rxPane.repaint();
                pushBgRecent(f);
                Config.put("bg.path", f.getAbsolutePath());
                sys("背景已设为 " + f.getName() + "（" + img.getWidth() + "×" + img.getHeight()
                        + "，" + BgTextPane.MODES[rxPane.mode()] + "）");
            } catch (Exception e) {
                err("加载图片失败: " + e.getMessage());
            }
        }

        private void pasteBackground() {
            try {
                Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                if (!cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
                    err("剪贴板里没有图片，先截个图再来");
                    return;
                }
                Image im = (Image) cb.getData(DataFlavor.imageFlavor);
                BufferedImage img = toBuffered(im);
                if (img == null) {
                    err("剪贴板图片无法转换");
                    return;
                }
                File dir = new File(System.getProperty("user.home"), ".yuci_com");
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    err("无法创建配置目录");
                    return;
                }
                File out = new File(dir, "bg-clipboard.png");
                ImageIO.write(img, "png", out);
                setBackground(out);
                sys("已把剪贴板图片存为背景: " + out.getAbsolutePath());
            } catch (Exception e) {
                err("粘贴图片失败: " + e.getMessage());
            }
        }

        private void clearBackground() {
            bgFile = null;
            rxPane.setImage(null);
            rxPane.repaint();
            Config.put("bg.path", "");
            sys("背景已清除");
        }

        private void applyBgMode(int m) {
            rxPane.setMode(m);
            Config.put("bg.mode", m);
            if (rxPane.hasImage()) sys("背景显示方式: " + BgTextPane.MODES[m]);
        }

        private void applyBgMask(int percent) {
            rxPane.setMask(percent / 100f);
            Config.put("bg.mask", percent);
        }

        private void pushBgRecent(File f) {
            String p = f.getAbsolutePath();
            bgRecent.remove(p);
            bgRecent.push(p);
            while (bgRecent.size() > 6) bgRecent.removeLast();
            Config.put("bg.recent", String.join("|", bgRecent));
        }

        private void rebuildBgRecentMenu() {
            if (bgRecentMenu == null) return;
            bgRecentMenu.removeAll();
            if (bgRecent.isEmpty()) {
                JMenuItem none = new JMenuItem("（暂无记录）");
                none.setEnabled(false);
                bgRecentMenu.add(none);
                return;
            }
            for (String p : bgRecent) {
                File f = new File(p);
                String label = f.getName() + "  —  " + shortenPath(p);
                JMenuItem mi = new JMenuItem(label);
                mi.setEnabled(f.isFile());
                mi.addActionListener(e -> setBackground(f));
                bgRecentMenu.add(mi);
            }
            bgRecentMenu.addSeparator();
            bgRecentMenu.add(item("清空最近记录", e -> {
                bgRecent.clear();
                Config.put("bg.recent", "");
            }));
        }

        private static String shortenPath(String p) {
            return p.length() <= 52 ? p : "…" + p.substring(p.length() - 51);
        }

        private void restoreBackground() {
            rxPane.setMode(clamp(Config.getInt("bg.mode", 0), 0, BgTextPane.MODES.length - 1));
            rxPane.setMask(clamp(Config.getInt("bg.mask", 65), 0, 95) / 100f);

            String rec = Config.get("bg.recent", "");
            if (!rec.isEmpty()) {
                for (String s : rec.split("\\|")) {
                    if (!s.isEmpty()) bgRecent.addLast(s);
                }
            }

            String path = Config.get("bg.path", "");
            if (path.isEmpty()) return;
            File f = new File(path);
            if (!f.isFile()) {
                sys("上次的背景图已不存在，已忽略: " + path);
                return;
            }
            try {
                BufferedImage img = ImageIO.read(f);
                if (img != null) {
                    bgFile = f;
                    rxPane.setImage(img);
                    sys("已恢复背景 " + f.getName() + "（" + BgTextPane.MODES[rxPane.mode()] + "）");
                }
            } catch (Exception ignored) {
            }
        }

        private static BufferedImage toBuffered(Image im) {
            if (im instanceof BufferedImage) return (BufferedImage) im;
            int w = im.getWidth(null);
            int h = im.getHeight(null);
            if (w <= 0 || h <= 0) return null;
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = out.createGraphics();
            g.drawImage(im, 0, 0, null);
            g.dispose();
            return out;
        }

        /* ---------- 对话框 ---------- */

        private void openCrcDialog() {
            CrcDialog.show(this, txArea, chkHexSend);
        }

        private void openFrameDialog() {
            FrameDialog.show(this, txArea, chkHexSend, (data, echo) -> sendBytes(data, echo));
        }

        private void showShortcuts() {
            String msg = """
                    Ctrl+Enter       发送
                    Ctrl+↑           取回上次发送内容
                    Ctrl+1 / Ctrl+2  手工发送 / 快捷发送
                    Ctrl+O           打开 / 关闭串口
                    Ctrl+R           重新打开串口
                    Ctrl+L           清空接收区
                    Ctrl+S           保存接收日志
                    Ctrl+Shift+O     打开接收日志
                    Ctrl+N           新建会话
                    Ctrl+H           HEX 显示开关
                    Ctrl+Shift+T     时间戳开关
                    Ctrl+P           暂停显示
                    Ctrl+K           CRC / 校验计算
                    Ctrl+G           协议组包
                    Ctrl+B           选择背景图
                    Ctrl+T           数据统计
                    Ctrl+滚轮         调整字号
                    F1  帮助     F5  刷新端口     F11  最大化""";
            JOptionPane.showMessageDialog(this, msg, "快捷键", JOptionPane.INFORMATION_MESSAGE);
        }

        private void showHelp() {
            String text = """
                    一、接线与开串口
                      1. USB-TTL 的 TXD 接板子的 PA10(RX)，RXD 接 PA9(TX)，GND 必须共地。
                      2. 模块的 3V3 / 5V 不要接到板子的 3V3 上；板子插着 ST-Link 时模块只接信号线和 GND 最安全。
                      3. 插上模块 -> 点「刷新」(F5) -> 选端口 -> 选波特率 -> 「打开串口」(Ctrl+O)。

                    二、收发
                      · 发送框用 Ctrl+Enter 发送；勾「HEX 发送」就按十六进制写，比如 AA 55 01。
                      · 「行尾」可以自动补 \\r\\n / \\n / \\r，大多数 AT 指令需要 \\r\\n。
                      · 勾「定时发送」后按间隔重复发，「次数」填 0 表示一直发。
                      · 接收区可切 HEX 显示、加时间戳、暂停；「断帧」超过设定时间没新数据就换行。

                    三、快捷发送（多页发送）
                      切到「快捷发送」页签，可以把常用的指令存成列表，双击即发，
                      还能「全部循环」按固定间隔轮询。列表存在 ~/.yuci_com/quicksend.properties。

                    四、协议与校验
                      · 「CRC / 校验计算」支持 SUM8、CRC8、CRC8-MAXIM、CRC16-MODBUS、CRC16-CCITT。
                      · 「协议组包」可自定义帧头、长度字段、校验、帧尾，生成后直接发送。

                    五、背景与外观
                      · 菜单「背景 -> 选择图片」，或直接点接收区上方的「背景…」。
                      · 支持拉伸 / 等比 / 居中 / 平铺，遮罩浓度越高文字越清楚。
                      · Ctrl+滚轮调字号，F11 最大化。

                    六、配置
                      所有设置保存在 ~/.yuci_com/config.properties，
                      可以用「文件 -> 导出配置」带到别的电脑上。
                    """;
            JTextArea area = new JTextArea(text, 24, 46);
            area.setEditable(false);
            area.setFont(new Font("Microsoft YaHei UI", Font.PLAIN, 12));
            area.setBackground(new Color(0xFA, 0xFA, 0xFA));
            area.setCaretPosition(0);
            JScrollPane sp = new JScrollPane(area);
            sp.setPreferredSize(new Dimension(560, 460));
            JOptionPane.showMessageDialog(this, sp, "快速上手", JOptionPane.PLAIN_MESSAGE);
        }

        private void showAbout() {
            String msg = APP + " " + VERSION + "\n\n"
                    + "串口调试助手，Java Swing + jSerialComm 原生实现。\n"
                    + "不依赖浏览器、不起本地服务，直接操作 COM 口。\n\n"
                    + "· 收发 / HEX / 时间戳 / 定时发送 / 文件发送\n"
                    + "· 5 种校验算法 + 自定义协议组包\n"
                    + "· 快捷发送多页列表、分包超时断帧\n"
                    + "· 接收区背景图片（拉伸 / 等比 / 居中 / 平铺 + 遮罩）\n\n"
                    + "jSerialComm 2.11.4  ·  Java " + System.getProperty("java.version") + "\n"
                    + "开源协议: MIT    项目地址: " + PROJECT_URL + "\n"
                    + "配置文件: " + new File(System.getProperty("user.home"), ".yuci_com/config.properties");
            JOptionPane.showMessageDialog(this, msg, "关于 " + APP, JOptionPane.INFORMATION_MESSAGE);
        }

        private void openStats() {
            long up = openAt == 0 ? 0 : (System.currentTimeMillis() - openAt) / 1000;
            StatsDialog.show(this, engine.isOpen(), engine.portName(),
                    rxBytes, txBytes, rxChunks, txFrames, up);
        }

        private void openProjectPage() {
            try {
                if (Desktop.isDesktopSupported()) {
                    Desktop.getDesktop().browse(new java.net.URI(PROJECT_URL));
                }
            } catch (Exception e) {
                JOptionPane.showMessageDialog(this, "项目地址: " + PROJECT_URL, APP, JOptionPane.INFORMATION_MESSAGE);
            }
        }

        /* ---------- 配置 ---------- */

        private void loadConfig() {
            String baud = Config.get("port.baud", "115200");
            cbBaud.setSelectedItem(baud);
            if (cbBaud.getSelectedIndex() < 0) ((JTextField) cbBaud.getEditor().getEditorComponent()).setText(baud);
            cbData.setSelectedItem(Config.get("port.data", "8"));
            cbParity.setSelectedIndex(clamp(Config.getInt("port.parity", 0), 0, 4));
            cbStop.setSelectedIndex(clamp(Config.getInt("port.stop", 0), 0, 2));
            cbFlow.setSelectedIndex(clamp(Config.getInt("port.flow", 0), 0, 3));
            fontSize = Config.getInt("view.font", 13);
            fontFamily = Config.get("view.fontFamily", "zh");
            refreshFonts();
            cbLineEnd.setSelectedIndex(clamp(Config.getInt("send.lineEnd", 1), 0, 3));

            String cs = Config.get("view.charset", "UTF-8");
            if (Arrays.asList(Enc.NAMES).contains(cs)) cbEncoding.setSelectedItem(cs);
            rxDec.setCharset((String) cbEncoding.getSelectedItem());
            cbGap.setSelectedIndex(clamp(Config.getInt("view.gap", 0), 0, 6));
            chkHexSend.setSelected(Config.getBool("send.hex", false));
            updateTxHint();
        }

        private void saveConfig() {
            Config.put("port.baud", String.valueOf(cbBaud.getEditor().getItem()).trim());
            Config.put("port.data", cbData.getSelectedItem());
            Config.put("port.parity", cbParity.getSelectedIndex());
            Config.put("port.stop", cbStop.getSelectedIndex());
            Config.put("port.flow", cbFlow.getSelectedIndex());
            Config.put("send.lineEnd", cbLineEnd.getSelectedIndex());
            Config.put("send.hex", chkHexSend.isSelected());
            Config.put("view.font", fontSize);
            Config.put("view.fontFamily", fontFamily);
            Config.put("view.hex", chkHexView.isSelected());
            Config.put("view.ts", chkTimestamp.isSelected());
            Config.put("view.scroll", chkAutoScroll.isSelected());
            Config.put("view.charset", cbEncoding.getSelectedItem());
            Config.put("view.gap", cbGap.getSelectedIndex());
            Config.put("bg.mode", rxPane.mode());
            Config.put("bg.mask", Math.round(rxPane.mask() * 100));
            Config.put("bg.path", bgFile == null ? "" : bgFile.getAbsolutePath());
            Config.put("bg.recent", String.join("|", bgRecent));
            String n = selectedPortName();
            if (n != null) Config.put("port.name", n);
            Config.save();
        }

        static int clamp(int v, int lo, int hi) {
            return Math.max(lo, Math.min(hi, v));
        }

        static String parityLetter(int p) {
            switch (p) {
                case SerialPort.ODD_PARITY:
                    return "O";
                case SerialPort.EVEN_PARITY:
                    return "E";
                case SerialPort.MARK_PARITY:
                    return "M";
                case SerialPort.SPACE_PARITY:
                    return "S";
                default:
                    return "N";
            }
        }

        static String stopLetter(int s) {
            switch (s) {
                case SerialPort.ONE_POINT_FIVE_STOP_BITS:
                    return "1.5";
                case SerialPort.TWO_STOP_BITS:
                    return "2";
                default:
                    return "1";
            }
        }
    }

    /* 顶部分隔线 */

    static final class MatteBorderTop extends EmptyBorder {
        MatteBorderTop() {
            super(1, 0, 0, 0);
        }

        @Override
        public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            g.setColor(new Color(0xD8, 0xD8, 0xD8));
            g.drawLine(x, y, x + w, y);
        }
    }

    /* ==================== CRC 计算对话框 ==================== */

    static final class CrcDialog {
        static void show(Component parent, JTextArea target, JCheckBox hexSend) {
            JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), "CRC / 校验计算", Dialog.ModalityType.APPLICATION_MODAL);
            d.setLayout(new BorderLayout(8, 8));

            JTextArea input = new JTextArea(5, 44);
            input.setFont(SessionPanel.mono(13));
            input.setText(hexSend.isSelected() ? "" : "");
            input.setLineWrap(true);

            JComboBox<String> kind = new JComboBox<>(Sum.KINDS);
            JLabel result = new JLabel("—");
            result.setFont(SessionPanel.mono(14));
            JLabel ascii = new JLabel(" ");
            ascii.setFont(SessionPanel.mono(12));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            top.add(new JLabel("输入内容（HEX，也支持 ASCII）"));
            JCheckBox chkAscii = new JCheckBox("按 ASCII 文本解释");
            top.add(chkAscii);

            JPanel center = new JPanel(new BorderLayout(6, 6));
            center.setBorder(new EmptyBorder(0, 12, 0, 12));
            center.add(new JScrollPane(input), BorderLayout.CENTER);

            JPanel opts = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
            opts.add(new JLabel("算法"));
            opts.add(kind);
            JButton btnCalc = new JButton("计算");
            JButton btnAppend = new JButton("追加到发送框");
            opts.add(btnCalc);
            opts.add(btnAppend);
            opts.add(result);
            opts.add(ascii);
            center.add(opts, BorderLayout.SOUTH);

            JButton close = new JButton("关闭");
            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            bottom.add(close);

            d.add(top, BorderLayout.NORTH);
            d.add(center, BorderLayout.CENTER);
            d.add(bottom, BorderLayout.SOUTH);

            final byte[][] last = new byte[1][];
            btnCalc.addActionListener(e -> {
                String txt = input.getText();
                byte[] data;
                if (chkAscii.isSelected()) {
                    data = txt.getBytes(StandardCharsets.UTF_8);
                } else {
                    data = Hex.parse(txt);
                }
                last[0] = data;
                byte[] ck = Sum.apply((String) kind.getSelectedItem(), data, 0, data.length);
                result.setText("结果 " + Hex.of(ck) + "   (" + data.length + " 字节)");
                ascii.setText(chkAscii.isSelected() ? "" : "ASCII: " + printable(data));
            });
            btnAppend.addActionListener(e -> {
                if (last[0] == null) btnCalc.doClick();
                byte[] ck = Sum.apply((String) kind.getSelectedItem(), last[0], 0, last[0].length);
                if (!hexSend.isSelected()) {
                    JOptionPane.showMessageDialog(d, "请先在主界面勾选「HEX 发送」。", "提示", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                target.append((target.getText().isEmpty() ? "" : " ") + Hex.of(ck));
                d.dispose();
            });
            close.addActionListener(e -> d.dispose());

            d.pack();
            d.setLocationRelativeTo(parent);
            d.setMinimumSize(new Dimension(620, 300));
            d.setVisible(true);
        }

        static String printable(byte[] d) {
            StringBuilder sb = new StringBuilder();
            for (byte b : d) {
                int c = b & 0xFF;
                sb.append(c >= 32 && c < 127 ? (char) c : '.');
            }
            return sb.toString();
        }
    }

    /* ==================== 协议组包对话框 ==================== */

    interface FrameSender {
        void send(byte[] data, String echo);
    }

    static final class FrameDialog {
        static void show(Component parent, JTextArea target, JCheckBox hexSend, FrameSender sender) {
            JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), "协议组包", Dialog.ModalityType.APPLICATION_MODAL);
            d.setLayout(new BorderLayout(8, 8));

            JTextField tfHeader = new JTextField("AA 55", 26);
            JTextField tfTail = new JTextField("0D 0A", 26);
            JTextArea payload = new JTextArea(5, 40);
            payload.setFont(SessionPanel.mono(13));
            payload.setLineWrap(true);
            payload.setText("01 02 03");
            JCheckBox chkLen = new JCheckBox("插入 1 字节长度字段");
            JCheckBox chkPayloadAscii = new JCheckBox("数据按 ASCII 文本");
            JComboBox<String> cbCheck = new JComboBox<>(new String[]{"NONE", "SUM8", "CRC8", "CRC8-MAXIM", "CRC16-MODBUS", "CRC16-CCITT"});
            cbCheck.setSelectedItem("CRC16-MODBUS");
            JTextArea preview = new JTextArea(3, 40);
            preview.setFont(SessionPanel.mono(13));
            preview.setEditable(false);
            preview.setLineWrap(true);
            JLabel info = new JLabel("—");

            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(new EmptyBorder(10, 14, 6, 14));
            GridBagConstraints gc = new GridBagConstraints();
            gc.insets = new Insets(4, 4, 4, 4);
            gc.anchor = GridBagConstraints.WEST;
            gc.gridx = 0;
            gc.gridy = 0;
            form.add(new JLabel("帧头 HEX"), gc);
            gc.gridx = 1;
            form.add(tfHeader, gc);
            gc.gridx = 2;
            form.add(new JLabel("帧尾 HEX"), gc);
            gc.gridx = 3;
            form.add(tfTail, gc);
            gc.gridx = 0;
            gc.gridy = 1;
            form.add(new JLabel("校验"), gc);
            gc.gridx = 1;
            form.add(cbCheck, gc);
            gc.gridx = 2;
            form.add(chkLen, gc);
            gc.gridx = 3;
            form.add(chkPayloadAscii, gc);
            gc.gridx = 0;
            gc.gridy = 2;
            form.add(new JLabel("数据"), gc);
            gc.gridx = 1;
            gc.gridwidth = 3;
            gc.fill = GridBagConstraints.HORIZONTAL;
            form.add(new JScrollPane(payload), gc);
            gc.gridx = 0;
            gc.gridy = 3;
            gc.gridwidth = 1;
            gc.fill = GridBagConstraints.NONE;
            form.add(new JLabel("预览"), gc);
            gc.gridx = 1;
            gc.gridwidth = 2;
            gc.fill = GridBagConstraints.HORIZONTAL;
            form.add(new JScrollPane(preview), gc);
            gc.gridx = 3;
            gc.gridwidth = 1;
            gc.fill = GridBagConstraints.NONE;
            form.add(info, gc);

            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            JButton btnPreview = new JButton("生成预览");
            JButton btnToTx = new JButton("填入发送框");
            JButton btnSend = new JButton("直接发送");
            JButton btnClose = new JButton("关闭");
            bottom.add(btnPreview);
            bottom.add(btnToTx);
            bottom.add(btnSend);
            bottom.add(btnClose);

            Runnable calc = () -> {
                Frame f = new Frame();
                f.header = Hex.parse(tfHeader.getText());
                f.tail = Hex.parse(tfTail.getText());
                f.withLen = chkLen.isSelected();
                f.payload = chkPayloadAscii.isSelected()
                        ? payload.getText().getBytes(StandardCharsets.UTF_8)
                        : Hex.parse(payload.getText());
                f.check = (String) cbCheck.getSelectedItem();
                byte[] out = f.build();
                preview.setText(Hex.of(out));
                info.setText(out.length + " 字节");
            };

            btnPreview.addActionListener(e -> calc.run());
            btnToTx.addActionListener(e -> {
                calc.run();
                if (!hexSend.isSelected()) {
                    JOptionPane.showMessageDialog(d, "请先在主界面勾选「HEX 发送」。", "提示", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                target.setText(preview.getText());
                d.dispose();
            });
            btnSend.addActionListener(e -> {
                calc.run();
                sender.send(Hex.parse(preview.getText()), "frame");
                d.dispose();
            });
            btnClose.addActionListener(e -> d.dispose());

            d.add(form, BorderLayout.CENTER);
            d.add(bottom, BorderLayout.SOUTH);
            d.pack();
            d.setLocationRelativeTo(parent);
            d.setMinimumSize(new Dimension(700, 380));
            calc.run();
            d.setVisible(true);
        }
    }

    /* ==================== 文件发送对话框 ==================== */

    interface BytesSent {
        void onBytes(long n);
    }

    static final class FileSendDialog {
        static void show(Component parent, SerialEngine engine, BytesSent cb) {
            JDialog d = new JDialog(SwingUtilities.getWindowAncestor(parent), "发送文件", Dialog.ModalityType.MODELESS);
            d.setLayout(new BorderLayout(8, 8));

            JTextField tfPath = new JTextField(34);
            tfPath.setEditable(false);
            JButton btnPick = new JButton("选择文件…");
            JTextField tfChunk = new JTextField("256", 5);
            JTextField tfDelay = new JTextField("10", 5);
            JProgressBar bar = new JProgressBar(0, 100);
            bar.setStringPainted(true);
            JLabel info = new JLabel("未选择文件");
            JButton btnStart = new JButton("开始发送");
            FlatButton btnStop = new FlatButton("停止", false);
            btnStop.setEnabled(false);

            JPanel form = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
            form.add(new JLabel("文件"));
            form.add(tfPath);
            form.add(btnPick);
            form.add(new JLabel("每帧字节"));
            form.add(tfChunk);
            form.add(new JLabel("帧间隔 ms"));
            form.add(tfDelay);

            JPanel center = new JPanel(new BorderLayout(6, 6));
            center.setBorder(new EmptyBorder(0, 12, 0, 12));
            center.add(form, BorderLayout.NORTH);
            JPanel mid = new JPanel(new BorderLayout(6, 6));
            mid.add(bar, BorderLayout.CENTER);
            mid.add(info, BorderLayout.SOUTH);
            center.add(mid, BorderLayout.CENTER);

            JPanel bottom = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 8));
            bottom.add(btnStart);
            bottom.add(btnStop);

            d.add(center, BorderLayout.CENTER);
            d.add(bottom, BorderLayout.SOUTH);

            final File[] file = new File[1];
            final SwingWorker<Void, Integer>[] worker = new SwingWorker[1];
            final boolean[] cancelled = {false};

            btnPick.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION) {
                    file[0] = fc.getSelectedFile();
                    tfPath.setText(file[0].getAbsolutePath());
                    info.setText("未发送  ·  " + file[0].length() + " 字节");
                }
            });

            btnStop.addActionListener(e -> {
                cancelled[0] = true;
                if (worker[0] != null) worker[0].cancel(true);
                btnStop.setEnabled(false);
                btnStart.setEnabled(true);
                info.setText("已停止");
            });

            btnStart.addActionListener(e -> {
                if (file[0] == null) {
                    JOptionPane.showMessageDialog(d, "先选择文件", "提示", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                final int chunk;
                final int delay;
                try {
                    chunk = Math.max(1, Integer.parseInt(tfChunk.getText().trim()));
                    delay = Math.max(0, Integer.parseInt(tfDelay.getText().trim()));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(d, "帧大小/间隔需为整数", "提示", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                cancelled[0] = false;
                btnStart.setEnabled(false);
                btnStop.setEnabled(true);
                final long total = file[0].length();

                worker[0] = new SwingWorker<>() {
                    @Override
                    protected Void doInBackground() throws Exception {
                        try (InputStream in = new FileInputStream(file[0])) {
                            byte[] buf = new byte[chunk];
                            long sent = 0;
                            int n;
                            while (!cancelled[0] && (n = in.read(buf)) > 0) {
                                byte[] chunkData = Arrays.copyOf(buf, n);
                                if (engine.write(chunkData) < 0) {
                                    throw new IllegalStateException("写入失败");
                                }
                                sent += n;
                                publish((int) (sent * 100 / Math.max(1, total)));
                                if (delay > 0) Thread.sleep(delay);
                            }
                            final long s = sent;
                            SwingUtilities.invokeLater(() -> cb.onBytes(s));
                        }
                        return null;
                    }

                    @Override
                    protected void process(List<Integer> chunks) {
                        int p = chunks.get(chunks.size() - 1);
                        bar.setValue(p);
                        info.setText("发送中 " + p + "%");
                    }

                    @Override
                    protected void done() {
                        btnStart.setEnabled(true);
                        btnStop.setEnabled(false);
                        if (!cancelled[0]) {
                            bar.setValue(100);
                            info.setText("发送完成  ·  " + total + " 字节");
                        }
                    }
                };
                worker[0].execute();
            });

            d.pack();
            d.setLocationRelativeTo(parent);
            d.setMinimumSize(new Dimension(700, 240));
            d.setVisible(true);
        }
    }

    /* ==================== 快捷发送（多页发送） ==================== */

    static final class QuickSendPanel extends JPanel {
        private final SessionPanel owner;
        private final List<QuickItem> items = new ArrayList<>();
        private final QuickModel model = new QuickModel();
        private final JTable table = new JTable(model);
        private final JTextField tfLoop = new JTextField("1000", 5);
        private final FlatButton btnLoop = new FlatButton("全部循环", false);
        private Timer loopTimer;
        private int loopIndex;

        QuickSendPanel(SessionPanel owner) {
            super(new BorderLayout(6, 4));
            this.owner = owner;

            items.addAll(QuickStore.load());
            if (items.isEmpty()) {
                items.add(new QuickItem("AT 握手", "AT", false, 1));
                items.add(new QuickItem("查询版本", "AT+GMR", false, 1));
                items.add(new QuickItem("HEX 示例", "AA 55 01 02", true, 0));
            }

            table.setRowHeight(26);
            table.setFont(Ui.font(12));
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setSelectionBackground(new Color(0xDC, 0xE9, 0xFD));
            table.setSelectionForeground(Ui.TEXT);
            table.setShowVerticalLines(false);
            table.setShowHorizontalLines(true);
            table.setGridColor(new Color(0xF0, 0xF2, 0xF5));
            table.setIntercellSpacing(new Dimension(0, 1));
            table.setBackground(Ui.SURFACE);
            table.getTableHeader().setReorderingAllowed(false);
            table.getTableHeader().setFont(Ui.font(12));
            table.getTableHeader().setBackground(Ui.PANEL);
            table.getTableHeader().setForeground(Ui.MUTED);
            table.getTableHeader().setPreferredSize(new Dimension(10, 28));
            table.getColumnModel().getColumn(0).setPreferredWidth(150);
            table.getColumnModel().getColumn(1).setPreferredWidth(60);
            table.getColumnModel().getColumn(2).setPreferredWidth(560);
            table.getSelectionModel().setSelectionInterval(0, 0);
            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 2) sendSelected();
                }
            });

            JScrollPane sp = new JScrollPane(table);
            sp.setPreferredSize(new Dimension(100, 82));
            add(sp, BorderLayout.CENTER);

            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 3));
            FlatButton btnSend = new FlatButton("发送选中", true);
            FlatButton btnAdd = new FlatButton("添加", false);
            FlatButton btnEdit = new FlatButton("编辑", false);
            FlatButton btnDel = new FlatButton("删除", false);
            FlatButton btnUp = new FlatButton("上移", false);
            FlatButton btnDown = new FlatButton("下移", false);
            FlatButton btnStop = new FlatButton("停止", false);
            btnSend.setFont(btnSend.getFont().deriveFont(Font.BOLD));

            bar.add(btnSend);
            bar.add(btnAdd);
            bar.add(btnEdit);
            bar.add(btnDel);
            bar.add(btnUp);
            bar.add(btnDown);
            bar.add(Box.createHorizontalStrut(8));
            bar.add(new JLabel("轮询间隔ms"));
            bar.add(tfLoop);
            bar.add(btnLoop);
            bar.add(btnStop);
            add(bar, BorderLayout.SOUTH);

            btnSend.addActionListener(e -> sendSelected());
            btnAdd.addActionListener(e -> edit(-1));
            btnEdit.addActionListener(e -> edit(table.getSelectedRow()));
            btnDel.addActionListener(e -> removeSelected());
            btnUp.addActionListener(e -> move(-1));
            btnDown.addActionListener(e -> move(1));
            btnLoop.addActionListener(e -> startLoop());
            btnStop.addActionListener(e -> stopLoop());
        }

        private void sendSelected() {
            int row = table.getSelectedRow();
            if (row < 0 || row >= items.size()) {
                owner.err("先在列表里选一条指令");
                return;
            }
            sendAt(row);
        }

        private void sendAt(int row) {
            if (!owner.engine.isOpen()) {
                owner.err("串口未打开，无法发送");
                stopLoop();
                return;
            }
            QuickItem q = items.get(row);
            byte[] d = q.bytes();
            if (d.length == 0) return;
            owner.sendBytes(d, q.hex ? Hex.of(d) : q.data);
        }

        private void removeSelected() {
            int row = table.getSelectedRow();
            if (row < 0 || row >= items.size()) return;
            items.remove(row);
            model.fireTableDataChanged();
            persist();
            if (!items.isEmpty()) {
                table.setRowSelectionInterval(Math.min(row, items.size() - 1), Math.min(row, items.size() - 1));
            }
        }

        private void move(int dir) {
            int row = table.getSelectedRow();
            int to = row + dir;
            if (row < 0 || to < 0 || to >= items.size()) return;
            QuickItem q = items.remove(row);
            items.add(to, q);
            model.fireTableDataChanged();
            table.setRowSelectionInterval(to, to);
            persist();
        }

        private void edit(int row) {
            boolean isNew = row < 0 || row >= items.size();
            QuickItem base = isNew ? new QuickItem("", "", false, 1) : items.get(row);

            JTextField tfName = new JTextField(base.name, 16);
            JTextArea taData = new JTextArea(base.data, 4, 40);
            taData.setFont(SessionPanel.mono(13));
            taData.setLineWrap(true);
            JCheckBox chkHex = new JCheckBox("HEX 格式", base.hex);
            JComboBox<String> cbEnd = new JComboBox<>(new String[]{"无", "\\r\\n", "\\n", "\\r"});
            cbEnd.setSelectedIndex(SessionPanel.clamp(base.lineEnd, 0, 3));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            top.add(new JLabel("名称"));
            top.add(tfName);
            top.add(chkHex);
            top.add(new JLabel("行尾"));
            top.add(cbEnd);

            JPanel box = new JPanel(new BorderLayout(6, 4));
            box.add(top, BorderLayout.NORTH);
            box.add(new JScrollPane(taData), BorderLayout.CENTER);
            box.setPreferredSize(new Dimension(520, 230));

            int r = JOptionPane.showConfirmDialog(this, box,
                    isNew ? "新增指令" : "编辑指令",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
            if (r != JOptionPane.OK_OPTION) return;

            QuickItem q = new QuickItem(
                    tfName.getText().trim().isEmpty() ? "未命名" : tfName.getText().trim(),
                    taData.getText(), chkHex.isSelected(), cbEnd.getSelectedIndex());
            if (isNew) items.add(q);
            else items.set(row, q);
            model.fireTableDataChanged();
            persist();
            if (isNew) table.setRowSelectionInterval(items.size() - 1, items.size() - 1);
        }

        private void persist() {
            QuickStore.save(items);
            owner.sys("快捷发送列表已保存（" + items.size() + " 条）");
        }

        private void startLoop() {
            if (items.isEmpty()) {
                owner.err("列表是空的，先添加几条指令");
                return;
            }
            int ms;
            try {
                ms = Math.max(50, Integer.parseInt(tfLoop.getText().trim()));
            } catch (Exception e) {
                owner.err("轮询间隔不是合法数字");
                return;
            }
            stopLoop();
            loopIndex = 0;
            loopTimer = new Timer(ms, e -> {
                if (!owner.engine.isOpen() || items.isEmpty()) {
                    stopLoop();
                    return;
                }
                sendAt(loopIndex % items.size());
                loopIndex++;
            });
            loopTimer.start();
            btnLoop.setEnabled(false);
            owner.sys("轮询已启动：间隔 " + ms + " ms，共 " + items.size() + " 条");
        }

        private void stopLoop() {
            if (loopTimer != null) {
                loopTimer.stop();
                loopTimer = null;
            }
            btnLoop.setEnabled(true);
        }

        private final class QuickModel extends AbstractTableModel {
            private final String[] cols = {"名称", "类型", "内容"};

            @Override
            public int getRowCount() {
                return items.size();
            }

            @Override
            public int getColumnCount() {
                return cols.length;
            }

            @Override
            public String getColumnName(int c) {
                return cols[c];
            }

            @Override
            public boolean isCellEditable(int r, int c) {
                return false;
            }

            @Override
            public Object getValueAt(int r, int c) {
                QuickItem q = items.get(r);
                switch (c) {
                    case 0:
                        return q.name;
                    case 1:
                        return q.hex ? "HEX" : "文本";
                    default:
                        return q.summary();
                }
            }
        }
    }

    /* ==================== 数据统计 ==================== */

    static final class StatsDialog {
        static void show(Component parent, boolean open, String port,
                         long rx, long tx, long rxCalls, long txFrames, long upSec) {
            StringBuilder sb = new StringBuilder();
            sb.append("串口状态   ").append(open ? "已连接  " + port : "未连接").append('\n');
            sb.append("连接时长   ").append(dur(upSec)).append('\n');
            sb.append("接收字节   ").append(SessionPanel.human(rx)).append("   (").append(rx).append(" B)\n");
            sb.append("发送字节   ").append(SessionPanel.human(tx)).append("   (").append(tx).append(" B)\n");
            sb.append("接收次数   ").append(rxCalls).append(" 次\n");
            sb.append("发送次数   ").append(txFrames).append(" 次\n");
            sb.append("平均接收   ").append(upSec > 0 ? SessionPanel.human(rx / Math.max(1, upSec)) + "/s" : "—").append('\n');
            sb.append("平均发送   ").append(upSec > 0 ? SessionPanel.human(tx / Math.max(1, upSec)) + "/s" : "—");
            JOptionPane.showMessageDialog(parent, sb.toString(), "数据统计", JOptionPane.INFORMATION_MESSAGE);
        }

        static String dur(long sec) {
            if (sec <= 0) return "—";
            long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
            if (h > 0) return h + " 小时 " + m + " 分 " + s + " 秒";
            if (m > 0) return m + " 分 " + s + " 秒";
            return s + " 秒";
        }
    }

    /**
     * 脚本化发送：内置 LuaJ（纯 Java 实现的 Lua 5.2），脚本里可以循环、判断、拼字符串。
     * 只装 base/table/string/math 四个库，**不装 io、os、package**，
     * 所以脚本碰不到文件系统，也执行不了系统命令。
     */
    static final class ScriptFrame extends JFrame {

        private static final String SAMPLE =
                "-- 每秒发一行 PING，共 10 次；设备没开就先提示\n" +
                        "for i = 1, 10 do\n" +
                        "  if not is_open() then\n" +
                        "    print(\"串口没打开，先点「打开串口」\")\n" +
                        "    break\n" +
                        "  end\n" +
                        "  send_line(\"PING \" .. i)\n" +
                        "  print(\"TX  PING \" .. i)\n" +
                        "  delay(1000)\n" +
                        "end\n" +
                        "print(\"结束\")";

        private final SessionPanel owner;
        private final JTextArea code = new JTextArea();
        private final JTextArea out = new JTextArea();
        private final FlatButton btnRun = new FlatButton("运行", true);
        private final FlatButton btnStop = new FlatButton("停止", false);
        private volatile boolean running;
        private volatile boolean stopping;
        private Thread runner;

        ScriptFrame(SessionPanel owner) {
            super("脚本发送  ·  Lua");
            this.owner = owner;
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setSize(760, 560);
            setMinimumSize(new Dimension(520, 380));
            setLocationRelativeTo(owner);
            setLayout(new BorderLayout());

            code.setText(SAMPLE);
            code.setFont(SessionPanel.monoZh(13));
            code.setTabSize(2);
            out.setFont(SessionPanel.monoZh(12));
            out.setEditable(false);
            out.setBackground(new Color(0xF7, 0xF7, 0xF7));

            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            btnRun.addActionListener(e -> run());
            btnStop.setEnabled(false);
            btnStop.addActionListener(e -> stop());
            FlatButton btnSample = new FlatButton("插入示例", false);
            btnSample.addActionListener(e -> code.setText(SAMPLE));
            FlatButton btnClear = new FlatButton("清空日志", false);
            btnClear.addActionListener(e -> out.setText(""));
            bar.add(btnRun);
            bar.add(btnStop);
            bar.add(btnSample);
            bar.add(btnClear);
            JPanel barWrap = new JPanel(new BorderLayout());
            barWrap.setBackground(Ui.SURFACE);
            barWrap.setBorder(new EmptyBorder(6, 8, 2, 8));
            barWrap.add(bar, BorderLayout.WEST);
            add(barWrap, BorderLayout.NORTH);

            JPanel body = new JPanel(new BorderLayout());
            body.setBackground(Ui.SURFACE);
            body.setBorder(new EmptyBorder(4, 10, 0, 10));
            JLabel lbCode = new JLabel("脚本");
            lbCode.setFont(Ui.font(11));
            lbCode.setForeground(Ui.MUTED);
            lbCode.setBorder(new EmptyBorder(0, 0, 4, 0));
            body.add(lbCode, BorderLayout.NORTH);
            body.add(new JScrollPane(code), BorderLayout.CENTER);

            JPanel bottom = new JPanel(new BorderLayout());
            bottom.setBackground(Ui.SURFACE);
            bottom.setBorder(new EmptyBorder(2, 10, 0, 10));
            JLabel lbOut = new JLabel("输出");
            lbOut.setFont(Ui.font(11));
            lbOut.setForeground(Ui.MUTED);
            lbOut.setBorder(new EmptyBorder(0, 0, 4, 0));
            bottom.add(lbOut, BorderLayout.NORTH);
            bottom.add(new JScrollPane(out), BorderLayout.CENTER);

            JSplitPane sp = new JSplitPane(JSplitPane.VERTICAL_SPLIT, body, bottom);
            sp.setResizeWeight(0.62);
            sp.setDividerSize(6);
            sp.setBorder(new EmptyBorder(0, 0, 0, 0));
            add(sp, BorderLayout.CENTER);

            JLabel hint = new JLabel(" 可用函数：send / send_line / send_hex / delay / print / is_open"
                    + "      沙箱里没有 io / os / require");
            hint.setFont(Ui.font(11));
            hint.setForeground(Ui.MUTED);
            hint.setBorder(new EmptyBorder(6, 10, 6, 10));
            add(hint, BorderLayout.SOUTH);
            getContentPane().setBackground(Ui.SURFACE);

            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    stop();
                }
            });
        }

        private void appendLog(String s) {
            SwingUtilities.invokeLater(() -> {
                out.append(s + "\n");
                out.setCaretPosition(out.getDocument().getLength());
            });
        }

        private void run() {
            if (running) return;
            final String src = code.getText();
            if (src.trim().isEmpty()) {
                appendLog("-- 脚本是空的 --");
                return;
            }
            running = true;
            stopping = false;
            btnRun.setEnabled(false);
            btnStop.setEnabled(true);
            runner = new Thread(() -> {
                try {
                    Globals g = buildGlobals();
                    g.load(src, "yuci_script").call();
                    appendLog("-- 脚本结束 --");
                } catch (Throwable t) {
                    if (!stopping) {
                        String m = t.getMessage();
                        appendLog("-- 出错: " + (m == null ? t.getClass().getSimpleName() : m) + " --");
                    } else {
                        appendLog("-- 已停止 --");
                    }
                } finally {
                    running = false;
                    SwingUtilities.invokeLater(() -> {
                        btnRun.setEnabled(true);
                        btnStop.setEnabled(false);
                    });
                }
            }, "yuci-script");
            runner.setDaemon(true);
            runner.start();
        }

        private void stop() {
            stopping = true;
            running = false;
            if (runner != null) runner.interrupt();
        }

        private Globals buildGlobals() {
            Globals g = new Globals();
            g.load(new JseBaseLib());
            /* TableLib 要靠 package.loaded 落脚，所以 PackageLib 得先装；
               装完立刻把搜索路径清空并摘掉 require，脚本就没法从磁盘加载任何东西了 */
            g.load(new PackageLib());
            g.load(new TableLib());
            g.load(new StringLib());
            g.load(new JseMathLib());
            /* LuaJ 默认不带编译器，不装这两句 load() 会报 "No compiler." */
            LoadState.install(g);
            LuaC.install(g);

            LuaValue pkg = g.get("package");
            if (!pkg.isnil()) {
                pkg.set("path", LuaValue.valueOf(""));
                pkg.set("cpath", LuaValue.valueOf(""));
            }
            g.set("require", LuaValue.NIL);
            g.set("dofile", LuaValue.NIL);
            g.set("loadfile", LuaValue.NIL);

            g.set("print", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs a) {
                    StringBuilder sb = new StringBuilder();
                    for (int i = 1; i <= a.narg(); i++) {
                        if (i > 1) sb.append('\t');
                        sb.append(a.arg(i).tojstring());
                    }
                    appendLog(sb.toString());
                    return NONE;
                }
            });

            g.set("send", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue a) {
                    final String s = a.tojstring();
                    onEdt(() -> owner.sendScript(s, false));
                    return NONE;
                }
            });

            g.set("send_line", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue a) {
                    final String s = a.tojstring();
                    onEdt(() -> owner.sendScript(s, true));
                    return NONE;
                }
            });

            g.set("send_hex", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue a) {
                    final String s = a.tojstring();
                    onEdt(() -> owner.sendScriptHex(s));
                    return NONE;
                }
            });

            g.set("delay", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue a) {
                    sleep(Math.max(0, a.checklong()));
                    return NONE;
                }
            });

            g.set("is_open", new ZeroArgFunction() {
                @Override
                public LuaValue call() {
                    return LuaValue.valueOf(owner.isPortOpen());
                }
            });
            return g;
        }

        /** 到点才返回；中途点「停止」会立刻抛出来打断脚本。 */
        private void sleep(long ms) {
            long end = System.currentTimeMillis() + ms;
            while (true) {
                if (!running) throw new LuaError("stopped");
                long left = end - System.currentTimeMillis();
                if (left <= 0) return;
                try {
                    Thread.sleep(Math.min(20, left));
                } catch (InterruptedException e) {
                    throw new LuaError("stopped");
                }
            }
        }

        private static void onEdt(Runnable r) {
            if (SwingUtilities.isEventDispatchThread()) {
                r.run();
                return;
            }
            try {
                SwingUtilities.invokeAndWait(r);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 外层窗口：用标签页管理多个 SessionPanel。每个标签页有自己的引擎、接收区、
     * 发送框和计数，所以能同时开两个 COM 口对发，或者一边烧录一边抓日志。
     */
    static final class AppFrame extends JFrame {

        private final JTabbedPane tabs = new JTabbedPane();
        private int seq;

        AppFrame() {
            super(APP + " " + VERSION + "  ·  串口调试助手");
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setMinimumSize(new Dimension(920, 640));
            setSize(1120, 780);
            setLocationRelativeTo(null);
            try {
                java.net.URL ico = AppFrame.class.getResource("/icon.png");
                if (ico != null) setIconImage(Toolkit.getDefaultToolkit().getImage(ico));
            } catch (Exception ignored) {
            }

            tabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
            tabs.setUI(new FlatTabbedPaneUI(Ui.SURFACE));
            tabs.setBackground(Ui.BG);
            tabs.setOpaque(true);
            getContentPane().setBackground(Ui.BG);
            tabs.addChangeListener(e -> syncMenu());
            tabs.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    maybePopup(e);
                }

                @Override
                public void mouseReleased(java.awt.event.MouseEvent e) {
                    maybePopup(e);
                }

                private void maybePopup(java.awt.event.MouseEvent e) {
                    if (!e.isPopupTrigger()) return;
                    final int i = tabs.indexAtLocation(e.getX(), e.getY());
                    if (i < 0) return;
                    tabs.setSelectedIndex(i);
                    JPopupMenu pm = new JPopupMenu();
                    JMenuItem close = new JMenuItem("关闭此标签页");
                    close.addActionListener(a -> closeSession((SessionPanel) tabs.getComponentAt(i)));
                    pm.add(close);
                    JMenuItem add = new JMenuItem("新建标签页");
                    add.addActionListener(a -> addSession());
                    pm.add(add);
                    pm.show(tabs, e.getX(), e.getY());
                }
            });

            add(tabs, BorderLayout.CENTER);
            addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    shutdown();
                }
            });

            addSession();
        }

        SessionPanel addSession() {
            SessionPanel p = new SessionPanel(this, ++seq);
            tabs.addTab(p.titleText(), p);
            tabs.setSelectedComponent(p);
            syncMenu();
            return p;
        }

        SessionPanel active() {
            Component c = tabs.getSelectedComponent();
            return c instanceof SessionPanel ? (SessionPanel) c : null;
        }

        /** 端口状态变了就刷新标签页标题和窗口标题。 */
        void retitle(SessionPanel p) {
            int i = tabs.indexOfComponent(p);
            if (i < 0) return;
            tabs.setTitleAt(i, p.titleText());
            if (tabs.getSelectedComponent() == p) syncMenu();
        }

        void closeSession(SessionPanel p) {
            if (p == null) return;
            int i = tabs.indexOfComponent(p);
            if (i < 0) return;
            if (tabs.getTabCount() == 1) {
                p.closePortAndClear();
                return;
            }
            p.closeSession();
            tabs.remove(i);
            syncMenu();
        }

        private void syncMenu() {
            SessionPanel p = active();
            if (p == null) return;
            setJMenuBar(p.menuBar());
            setTitle(APP + " " + VERSION + "  ·  " + p.titleText());
        }

        void shutdown() {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                Component c = tabs.getComponentAt(i);
                if (c instanceof SessionPanel) ((SessionPanel) c).closeSession();
            }
            dispose();
            System.exit(0);
        }
    }

    /** 一条收发记录，导出 CSV / Excel 用。 */
    static final class Rec {
        final long t;
        final boolean tx;
        final byte[] data;

        Rec(long t, boolean tx, byte[] data) {
            this.t = t;
            this.tx = tx;
            this.data = data;
        }
    }

    /**
     * 极简 xlsx 写入器：手写 OOXML，不引 POI（那会把打包体积撑大好几兆）。
     * 只支持「一个工作表 + 纯文本/数字单元格」，导出日志够用了。
     */
    static final class Xlsx {

        private Xlsx() {
        }

        static void write(File f, String sheetName, List<String[]> rows) throws IOException {
            try (ZipOutputStream z = new ZipOutputStream(new FileOutputStream(f))) {
                put(z, "[Content_Types].xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                                + "<Override PartName=\"/xl/worksheets/sheet1.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>"
                                + "</Types>");
                put(z, "_rels/.rels",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                                + "</Relationships>");
                put(z, "xl/workbook.xml",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\""
                                + " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
                                + "<sheets><sheet name=\"" + esc(sheetName) + "\" sheetId=\"1\" r:id=\"rId1\"/></sheets>"
                                + "</workbook>");
                put(z, "xl/_rels/workbook.xml.rels",
                        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                                + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet1.xml\"/>"
                                + "</Relationships>");

                StringBuilder sb = new StringBuilder(rows.size() * 96 + 512);
                sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
                        .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">")
                        .append("<cols><col min=\"1\" max=\"1\" width=\"12\" customWidth=\"1\"/>")
                        .append("<col min=\"2\" max=\"2\" width=\"6\" customWidth=\"1\"/>")
                        .append("<col min=\"3\" max=\"3\" width=\"7\" customWidth=\"1\"/>")
                        .append("<col min=\"4\" max=\"4\" width=\"48\" customWidth=\"1\"/>")
                        .append("<col min=\"5\" max=\"5\" width=\"56\" customWidth=\"1\"/></cols>")
                        .append("<sheetData>");
                for (int r = 0; r < rows.size(); r++) {
                    String[] row = rows.get(r);
                    sb.append("<row r=\"").append(r + 1).append("\">");
                    for (int c = 0; c < row.length; c++) {
                        String v = row[c] == null ? "" : row[c];
                        String ref = (char) ('A' + c) + String.valueOf(r + 1);
                        if (r > 0 && isNumber(v)) {
                            sb.append("<c r=\"").append(ref).append("\"><v>").append(v).append("</v></c>");
                        } else {
                            sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is>")
                                    .append("<t xml:space=\"preserve\">").append(esc(v))
                                    .append("</t></is></c>");
                        }
                    }
                    sb.append("</row>");
                }
                sb.append("</sheetData></worksheet>");
                put(z, "xl/worksheets/sheet1.xml", sb.toString());
            }
        }

        private static void put(ZipOutputStream z, String name, String content) throws IOException {
            z.putNextEntry(new ZipEntry(name));
            z.write(content.getBytes(StandardCharsets.UTF_8));
            z.closeEntry();
        }

        /** 只用它判断「这格能不能当数字存」，宁可保守一点。 */
        static boolean isNumber(String s) {
            if (s.isEmpty() || s.length() > 20) return false;
            boolean dot = false, digit = false;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c == '-' && i == 0) continue;
                if (c == '.') {
                    if (dot) return false;
                    dot = true;
                    continue;
                }
                if (c < '0' || c > '9') return false;
                digit = true;
            }
            return digit;
        }

        static String esc(String s) {
            StringBuilder b = new StringBuilder(s.length() + 8);
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '&': b.append("&amp;"); break;
                    case '<': b.append("&lt;"); break;
                    case '>': b.append("&gt;"); break;
                    case '"': b.append("&quot;"); break;
                    default:
                        if (c < 0x20 && c != '\n' && c != '\t') b.append(' ');
                        else b.append(c);
                }
            }
            return b.toString();
        }
    }

    /**
     * 把收到的数字直接画成曲线。
     * 一行里的数字按出现顺序当作 CH1、CH2…，端口每收到一帧就追加一个点。
     */
    static final class ChartFrame extends JFrame {

        private static final int MAX_POINTS = 4000;
        private static final Pattern NUM = Pattern.compile("-?\\d+(?:\\.\\d+)?");
        private static final Color[] COLORS = {
                new Color(0xD0, 0x3B, 0x3B), new Color(0x2E, 0x7D, 0xD3),
                new Color(0x2E, 0x9E, 0x5B), new Color(0xC8, 0x7A, 0x1E),
                new Color(0x8A, 0x4C, 0xC8), new Color(0x1F, 0x9E, 0xA8)};

        private final ArrayDeque<double[]> pts = new ArrayDeque<>();
        private int channels = 1;
        private long total;
        private boolean paused;
        private boolean autoScale = true;
        private boolean frozen;
        private double fMin, fMax;

        private final PlotPanel plot = new PlotPanel();
        private final JLabel lblInfo = new JLabel("等待数据…");

        ChartFrame(Window owner) {
            super("数据曲线图");
            setDefaultCloseOperation(DISPOSE_ON_CLOSE);
            setSize(800, 520);
            setMinimumSize(new Dimension(480, 320));
            setLocationRelativeTo(owner);
            setLayout(new BorderLayout(0, 0));

            JPanel bar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            JCheckBox chkPause = new JCheckBox("暂停");
            chkPause.addActionListener(e -> paused = chkPause.isSelected());
            JButton btnClear = new JButton("清空");
            btnClear.addActionListener(e -> clear());
            JCheckBox chkAuto = new JCheckBox("自动量程", true);
            chkAuto.addActionListener(e -> {
                autoScale = chkAuto.isSelected();
                frozen = false;
                plot.repaint();
            });
            bar.add(chkPause);
            bar.add(btnClear);
            bar.add(chkAuto);
            bar.add(new JLabel("  "));
            bar.add(lblInfo);
            add(bar, BorderLayout.NORTH);
            add(plot, BorderLayout.CENTER);

            JLabel hint = new JLabel("一行里的数字按顺序画成 CH1、CH2…（只在文本显示模式采样）");
            hint.setForeground(new Color(0x77, 0x77, 0x77));
            hint.setBorder(new EmptyBorder(2, 8, 6, 8));
            add(hint, BorderLayout.SOUTH);
        }

        void feed(String text) {
            if (paused || text == null || text.isEmpty()) return;
            boolean added = false;
            for (String line : text.split("\n")) {
                double[] v = nums(line);
                if (v == null) continue;
                pts.addLast(v);
                while (pts.size() > MAX_POINTS) pts.removeFirst();
                channels = Math.max(channels, v.length);
                total++;
                added = true;
            }
            if (added) {
                lblInfo.setText("点 " + total + " · 通道 " + channels);
                plot.repaint();
            }
        }

        private static double[] nums(String line) {
            Matcher m = NUM.matcher(line);
            double[] buf = new double[8];
            int n = 0;
            while (m.find() && n < buf.length) {
                try {
                    buf[n++] = Double.parseDouble(m.group());
                } catch (NumberFormatException ignored) {
                }
            }
            return n == 0 ? null : Arrays.copyOf(buf, n);
        }

        private void clear() {
            pts.clear();
            total = 0;
            channels = 1;
            frozen = false;
            lblInfo.setText("等待数据…");
            plot.repaint();
        }

        static String num(double v) {
            double a = Math.abs(v);
            if (a >= 100000 || (a > 0 && a < 0.001)) return String.format(Locale.ROOT, "%.2e", v);
            if (a >= 100) return String.format(Locale.ROOT, "%.0f", v);
            if (a >= 1) return String.format(Locale.ROOT, "%.2f", v);
            return String.format(Locale.ROOT, "%.3f", v);
        }

        private final class PlotPanel extends JPanel {

            PlotPanel() {
                setBackground(Color.WHITE);
                setPreferredSize(new Dimension(760, 400));
            }

            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                int w = getWidth(), h = getHeight();
                int left = 62, right = 14, top = 26, bottom = 30;
                int pw = w - left - right, ph = h - top - bottom;
                if (pw < 24 || ph < 24) {
                    g2.dispose();
                    return;
                }

                List<double[]> snap = new ArrayList<>(pts);
                double min = Double.MAX_VALUE, max = -Double.MAX_VALUE;
                for (double[] v : snap) {
                    for (double x : v) {
                        if (x < min) min = x;
                        if (x > max) max = x;
                    }
                }
                if (min > max) {
                    min = 0;
                    max = 1;
                }
                if (autoScale) {
                    fMin = min;
                    fMax = max;
                    frozen = true;
                } else if (frozen) {
                    min = fMin;
                    max = fMax;
                }
                if (max - min < 1e-9) {
                    min -= 1;
                    max += 1;
                }
                double pad = (max - min) * 0.08;
                double lo = min - pad, hi = max + pad;

                /* 网格 + Y 轴刻度 */
                g2.setFont(new Font("Dialog", Font.PLAIN, 11));
                for (int i = 0; i <= 4; i++) {
                    int y = top + ph * i / 4;
                    g2.setColor(new Color(0xED, 0xED, 0xED));
                    g2.drawLine(left, y, left + pw, y);
                    g2.setColor(new Color(0x66, 0x66, 0x66));
                    String s = num(hi - (hi - lo) * i / 4.0);
                    g2.drawString(s, left - 8 - g2.getFontMetrics().stringWidth(s), y + 4);
                }
                g2.setColor(new Color(0xBB, 0xBB, 0xBB));
                g2.drawLine(left, top, left, top + ph);
                g2.drawLine(left, top + ph, left + pw, top + ph);

                /* 曲线 */
                int n = snap.size();
                g2.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                for (int ch = 0; ch < channels; ch++) {
                    g2.setColor(COLORS[ch % COLORS.length]);
                    Path2D.Double path = new Path2D.Double();
                    boolean started = false;
                    for (int i = 0; i < n; i++) {
                        double[] v = snap.get(i);
                        if (ch >= v.length) {
                            started = false;
                            continue;
                        }
                        double x = n == 1 ? left + pw / 2.0 : left + pw * (i / (double) (n - 1));
                        double y = top + ph * (hi - v[ch]) / (hi - lo);
                        if (!started) {
                            path.moveTo(x, y);
                            started = true;
                        } else {
                            path.lineTo(x, y);
                        }
                    }
                    g2.draw(path);
                }

                /* 图例 */
                int lx = left + 6;
                for (int ch = 0; ch < channels; ch++) {
                    g2.setColor(COLORS[ch % COLORS.length]);
                    g2.fillRect(lx, top - 14, 11, 3);
                    g2.setColor(new Color(0x44, 0x44, 0x44));
                    g2.drawString("CH" + (ch + 1), lx + 15, top - 8);
                    lx += 58;
                }

                if (n == 0) {
                    g2.setColor(new Color(0xAA, 0xAA, 0xAA));
                    g2.setFont(new Font("Dialog", Font.PLAIN, 13));
                    String t = "还没有数据 —— 端口收到数字后这里会自动出曲线";
                    g2.drawString(t, left + (pw - g2.getFontMetrics().stringWidth(t)) / 2, top + ph / 2);
                }
                g2.dispose();
            }
        }
    }
}
