package com.function.cardissuer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/** Edits Android binary XML without changing resource IDs or rebuilding resources. */
final class BinaryManifest {
    private static final int STRING_POOL = 0x0001;
    private static final int RESOURCE_MAP = 0x0180;
    private static final int START = 0x0102;
    private static final int END = 0x0103;
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private final List<byte[]> chunks = new ArrayList<>();
    private final List<String> strings = new ArrayList<>();
    private final List<Integer> resources = new ArrayList<>();
    String packageName;
    String launcher;
    String originalFactory = "";
    String applicationClass = "android.app.Application";
    final List<String> isolatedServices = new ArrayList<>();
    final java.util.Map<String, Boolean> isolatedExports = new java.util.HashMap<>();
    final java.util.Map<String, String> isolatedProcesses = new java.util.HashMap<>();
    final java.util.Set<String> sharedIsolatedServices = new java.util.HashSet<>();

    BinaryManifest(byte[] xml) throws IOException {
        if (xml.length < 8 || u16(xml, 0) != 3 || i32(xml, 4) != xml.length) {
            throw new IOException("AndroidManifest.xml 格式无效");
        }
        for (int offset = u16(xml, 2); offset < xml.length;) {
            if (offset + 8 > xml.length) throw new IOException("Manifest 数据截断");
            int size = i32(xml, offset + 4);
            if (size < 8 || size > xml.length - offset) throw new IOException("Manifest 区块损坏");
            byte[] chunk = Arrays.copyOfRange(xml, offset, offset + size);
            chunks.add(chunk);
            if (u16(chunk, 0) == STRING_POOL) readStrings(chunk);
            if (u16(chunk, 0) == RESOURCE_MAP) {
                for (int i = 8; i < chunk.length; i += 4) resources.add(i32(chunk, i));
            }
            offset += size;
        }
        if (strings.isEmpty()) throw new IOException("Manifest 缺少字符串表");
    }

    byte[] protect() throws IOException {
        int appEnd = -1;
        String component = null;
        String currentService = null;
        boolean explicitServiceExport = false;
        boolean hasSdk = false;
        for (int i = 0; i < chunks.size(); i++) {
            byte[] chunk = chunks.get(i);
            int type = u16(chunk, 0);
            if (type != START && type != END) continue;
            String tag = string(i32(chunk, 20));
            if (type == END) {
                if (tag.equals("application")) appEnd = i;
                if (tag.equals("activity") || tag.equals("activity-alias")) component = null;
                if (tag.equals("service")) currentService = null;
                continue;
            }
            if (tag.equals("manifest")) {
                packageName = attributeString(chunk, "package", false);
                if (packageName == null || packageName.isEmpty()) throw new IOException("无法读取包名");
                if (attributeString(chunk, "split", false) != null
                        || attributeString(chunk, "sharedUserId", true) != null) {
                    throw new IOException("当前支持独立 APK，不支持拆分包或 sharedUserId 应用");
                }
            }
            if (tag.equals("uses-sdk")) {
                hasSdk = true;
                int current = attributeInt(chunk, "minSdkVersion", true, 1);
                chunk = setAttribute(chunk, "minSdkVersion", android.R.attr.minSdkVersion,
                        0x10, Math.max(29, current));
            }
            if (tag.equals("uses-split")) throw new IOException("当前不支持依赖拆分安装的 APK");
            if (tag.equals("application")) {
                String declaredApplication = attributeString(chunk, "name", true);
                if (declaredApplication != null) applicationClass = fullName(declaredApplication);
                if (attributeInt(chunk, "isSplitRequired", true, 0) != 0) {
                    throw new IOException("此 APK 需要拆分包，请选择完整独立安装包");
                }
                originalFactory = attributeString(chunk, "appComponentFactory", true);
                if (originalFactory == null) originalFactory = "";
                if (originalFactory.startsWith("com.function.protect.")) {
                    throw new IOException("此 APK 已由本工具加密，请选择原始 APK");
                }
                chunk = setAttribute(chunk, "appComponentFactory", android.R.attr.appComponentFactory,
                        3, index("com.function.protect.ShellFactory"));
                chunk = setAttribute(chunk, "extractNativeLibs", android.R.attr.extractNativeLibs, 0x12, -1);
                chunk = setAttribute(chunk, "hasCode", android.R.attr.hasCode, 0x12, -1);
                chunk = setAttribute(chunk, "debuggable", android.R.attr.debuggable, 0x12, 0);
                chunk = setAttribute(chunk, "allowBackup", android.R.attr.allowBackup, 0x12, 0);
            }
            if (tag.equals("meta-data") && "com.android.vending.splits.required".equals(
                    attributeString(chunk, "name", true)) && attributeInt(chunk, "value", true, 0) != 0) {
                throw new IOException("此 APK 需要拆分包，请选择完整独立安装包");
            }
            if (tag.equals("activity") || tag.equals("activity-alias")) {
                component = attributeString(chunk, "name", true);
            }
            if (tag.equals("service") && attributeInt(chunk, "isolatedProcess", true, 0) != 0) {
                String service = attributeString(chunk, "name", true);
                if (service == null) throw new IOException("隔离服务缺少名称");
                isolatedServices.add(fullName(service));
                isolatedExports.put(fullName(service), attributeInt(chunk, "exported", true, 0) != 0);
                currentService = fullName(service);
                explicitServiceExport = attribute(chunk, "exported", true) != null;
                String process = attributeString(chunk, "process", true);
                isolatedProcesses.put(currentService, process == null ? currentService : process);
                if (attributeInt(chunk, "allowSharedIsolatedProcess", true, 0) != 0) sharedIsolatedServices.add(currentService);
            }
            if (tag.equals("intent-filter") && currentService != null && !explicitServiceExport) isolatedExports.put(currentService, true);
            if (tag.equals("category") && "android.intent.category.LAUNCHER".equals(
                    attributeString(chunk, "name", true)) && component != null) {
                if (launcher == null) launcher = fullName(component);
                chunk = setAttribute(chunk, "name", android.R.attr.name, 3,
                        index("com.function.protect.ORIGINAL_LAUNCHER"));
            }
            chunks.set(i, chunk);
        }
        if (appEnd < 0 || launcher == null) throw new IOException("APK 没有可用的启动界面");
        List<byte[]> gate = new ArrayList<>();
        byte[] activity = start("activity");
        activity = setAttribute(activity, "name", android.R.attr.name, 3,
                index("com.function.protect.GateActivity"));
        activity = setAttribute(activity, "exported", android.R.attr.exported, 0x12, -1);
        gate.add(activity);
        gate.add(start("intent-filter"));
        gate.add(setAttribute(start("action"), "name", android.R.attr.name, 3,
                index("android.intent.action.MAIN")));
        gate.add(end("action"));
        gate.add(setAttribute(start("category"), "name", android.R.attr.name, 3,
                index("android.intent.category.LAUNCHER")));
        gate.add(end("category"));
        gate.add(end("intent-filter"));
        gate.add(end("activity"));
        byte[] restart = setAttribute(start("activity"), "name", android.R.attr.name, 3,
                index("com.function.protect.RestartActivity"));
        restart = setAttribute(restart, "exported", android.R.attr.exported, 0x12, 0);
        restart = setAttribute(restart, "process", android.R.attr.process, 3,
                index(":function_protect_restart_v2"));
        restart = setAttribute(restart, "taskAffinity", android.R.attr.taskAffinity, 3,
                index(packageName + ".function_protect_restart_v2"));
        restart = setAttribute(restart, "excludeFromRecents", android.R.attr.excludeFromRecents, 0x12, -1);
        gate.add(restart);
        gate.add(end("activity"));
        chunks.addAll(appEnd, gate);
        if (!hasSdk) {
            int rootStart = 0;
            while (u16(chunks.get(rootStart), 0) != START) rootStart++;
            byte[] sdk = setAttribute(start("uses-sdk"), "minSdkVersion", android.R.attr.minSdkVersion, 0x10, 29);
            chunks.addAll(rootStart + 1, Arrays.asList(sdk, end("uses-sdk")));
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        boolean mapWritten = false;
        for (byte[] chunk : chunks) {
            int type = u16(chunk, 0);
            if (type == STRING_POOL) {
                body.write(writeStrings());
                body.write(writeResources());
                mapWritten = true;
            } else if (type != RESOURCE_MAP) body.write(chunk);
        }
        if (!mapWritten) throw new IOException("无法输出 Manifest 字符串表");
        return ByteBuffer.allocate(8 + body.size()).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) 3).putShort((short) 8).putInt(8 + body.size())
                .put(body.toByteArray()).array();
    }

    private String fullName(String name) {
        if (name.startsWith(".")) return packageName + name;
        return name.contains(".") ? name : packageName + "." + name;
    }

    private String attributeString(byte[] node, String name, boolean android) {
        byte[] attr = attribute(node, name, android);
        if (attr == null) return null;
        int raw = i32(attr, 8);
        return raw >= 0 ? string(raw) : (attr[15] == 3 ? string(i32(attr, 16)) : null);
    }

    private int attributeInt(byte[] node, String name, boolean android, int fallback) {
        byte[] attr = attribute(node, name, android);
        return attr == null ? fallback : i32(attr, 16);
    }

    private byte[] attribute(byte[] node, String name, boolean android) {
        int offset = 16 + u16(node, 24);
        for (int i = 0; i < u16(node, 28); i++) {
            int at = offset + i * u16(node, 26);
            String ns = string(i32(node, at));
            if (name.equals(string(i32(node, at + 4))) && (android ? ANDROID.equals(ns) : ns == null)) {
                return Arrays.copyOfRange(node, at, at + 20);
            }
        }
        return null;
    }

    private byte[] setAttribute(byte[] node, String name, int resource, int type, int value) {
        int nameIndex = index(name);
        while (resources.size() <= nameIndex) resources.add(0);
        resources.set(nameIndex, resource);
        List<byte[]> attrs = new ArrayList<>();
        int offset = 16 + u16(node, 24);
        for (int i = 0; i < u16(node, 28); i++) {
            byte[] attr = Arrays.copyOfRange(node, offset + i * 20, offset + (i + 1) * 20);
            if (!(name.equals(string(i32(attr, 4))) && ANDROID.equals(string(i32(attr, 0))))) attrs.add(attr);
        }
        attrs.add(ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(index(ANDROID)).putInt(nameIndex).putInt(type == 3 ? value : -1)
                .putShort((short) 8).put((byte) 0).put((byte) type).putInt(value).array());
        attrs.sort(Comparator.comparingInt(attr -> resourceId(i32(attr, 4))));
        byte[] result = Arrays.copyOf(node, 36 + attrs.size() * 20);
        ByteBuffer buffer = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(4, result.length);
        buffer.putShort(24, (short) 20).putShort(26, (short) 20).putShort(28, (short) attrs.size());
        buffer.putShort(30, (short) 0).putShort(32, (short) 0).putShort(34, (short) 0);
        buffer.position(36);
        for (byte[] attr : attrs) buffer.put(attr);
        return result;
    }

    private int resourceId(int stringIndex) {
        return stringIndex >= resources.size() ? 0 : resources.get(stringIndex);
    }

    private byte[] start(String tag) {
        return ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) START).putShort((short) 16).putInt(36).putInt(0).putInt(-1)
                .putInt(-1).putInt(index(tag)).putShort((short) 20).putShort((short) 20)
                .putShort((short) 0).putShort((short) 0).putShort((short) 0).putShort((short) 0).array();
    }

    private byte[] end(String tag) {
        return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putShort((short) END).putShort((short) 16).putInt(24).putInt(0).putInt(-1)
                .putInt(-1).putInt(index(tag)).array();
    }

    private void readStrings(byte[] pool) throws IOException {
        int count = i32(pool, 8);
        if (i32(pool, 12) != 0) throw new IOException("暂不支持含样式的 Manifest 字符串表");
        int dataStart = i32(pool, 20);
        boolean utf8 = (i32(pool, 16) & 0x100) != 0;
        for (int i = 0; i < count; i++) {
            int at = dataStart + i32(pool, u16(pool, 2) + i * 4);
            int[] cursor = {at};
            if (utf8) {
                length8(pool, cursor);
                int bytes = length8(pool, cursor);
                strings.add(new String(pool, cursor[0], bytes, StandardCharsets.UTF_8));
            } else {
                int length = u16(pool, cursor[0]);
                cursor[0] += 2;
                if ((length & 0x8000) != 0) {
                    length = ((length & 0x7fff) << 16) | u16(pool, cursor[0]);
                    cursor[0] += 2;
                }
                strings.add(new String(pool, cursor[0], length * 2, StandardCharsets.UTF_16LE));
            }
        }
    }

    private byte[] writeStrings() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        List<Integer> offsets = new ArrayList<>();
        for (String value : strings) {
            offsets.add(bytes.size());
            byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
            writeLength8(bytes, value.length());
            writeLength8(bytes, utf8.length);
            bytes.write(utf8);
            bytes.write(0);
        }
        while (bytes.size() % 4 != 0) bytes.write(0);
        int start = 28 + offsets.size() * 4;
        ByteBuffer pool = ByteBuffer.allocate(start + bytes.size()).order(ByteOrder.LITTLE_ENDIAN);
        pool.putShort((short) STRING_POOL).putShort((short) 28).putInt(pool.capacity())
                .putInt(strings.size()).putInt(0).putInt(0x100).putInt(start).putInt(0);
        for (int offset : offsets) pool.putInt(offset);
        pool.put(bytes.toByteArray());
        return pool.array();
    }

    private byte[] writeResources() {
        ByteBuffer result = ByteBuffer.allocate(8 + resources.size() * 4).order(ByteOrder.LITTLE_ENDIAN);
        result.putShort((short) RESOURCE_MAP).putShort((short) 8).putInt(result.capacity());
        for (int id : resources) result.putInt(id);
        return result.array();
    }

    private int index(String value) {
        int found = strings.indexOf(value);
        if (found >= 0) return found;
        strings.add(value);
        return strings.size() - 1;
    }

    private String string(int index) { return index < 0 ? null : strings.get(index); }
    private static int u16(byte[] bytes, int at) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getShort(at) & 0xffff;
    }
    private static int i32(byte[] bytes, int at) {
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(at);
    }
    private static int length8(byte[] bytes, int[] cursor) {
        int length = bytes[cursor[0]++] & 0xff;
        return (length & 0x80) == 0 ? length : ((length & 0x7f) << 8) | (bytes[cursor[0]++] & 0xff);
    }
    private static void writeLength8(ByteArrayOutputStream out, int length) throws IOException {
        if (length > 0x7fff) throw new IOException("Manifest 字符串过长");
        if (length > 0x7f) out.write((length >> 8) | 0x80);
        out.write(length & 0xff);
    }
}
