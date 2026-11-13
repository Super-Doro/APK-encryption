package com.function.script;

import com.function.protocol.ActivationKeys;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyFactory;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.json.JSONObject;

public final class ScriptRunner {
    public static void main(String[] args) {
        File temporary = null;
        int exit = 1;
        try {
            if (args.length < 3) throw new Exception("缺少脚本路径或专属密钥");
            File source = new File(args[0]).getCanonicalFile();
            String encoded;
            try (BufferedReader reader = Files.newBufferedReader(source.toPath(), StandardCharsets.UTF_8)) {
                reader.readLine();
                String configLine = reader.readLine();
                String marker = "# FUNCTION_CONFIG=";
                if (configLine == null || !configLine.startsWith(marker)) throw new Exception("脚本保护配置损坏");
                encoded = configLine.substring(marker.length());
            }
            JSONObject config = new JSONObject(new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8));
            byte[] id = Base64.getUrlDecoder().decode(config.getString("id"));
            java.security.PublicKey issuer = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(
                    Base64.getDecoder().decode(config.getString("publicKey"))));
            byte[] key = ActivationKeys.open(args[1], id, config.getString("packageName"), issuer);
            File directory = new File(args[2]).getCanonicalFile();
            temporary = File.createTempFile("source-", ".sh", directory);
            temporary.setReadable(false, false);
            temporary.setWritable(false, false);
            temporary.setReadable(true, true);
            temporary.setWritable(true, true);
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                if (config.optString("payloadFormat").equals("chunked-v1")) {
                    try (java.io.InputStream input = openPayload(source)) {
                        ActivationKeys.decryptStream(input, output, key, ActivationKeys.dexAad(id, "script.sh"));
                    }
                } else {
                    byte[] plain = ActivationKeys.decrypt(Base64.getDecoder().decode(config.getString("payload")),
                            key, ActivationKeys.dexAad(id, "script.sh"));
                    output.write(plain);
                    Arrays.fill(plain, (byte) 0);
                }
            } finally { Arrays.fill(key, (byte) 0); }
            List<String> command = new ArrayList<>(Arrays.asList("/system/bin/sh", temporary.getPath()));
            command.addAll(Arrays.asList(args).subList(3, args.length));
            ProcessBuilder builder = new ProcessBuilder(command).inheritIO();
            exit = builder.start().waitFor();
        } catch (Exception error) {
            System.err.println("解密执行失败 " + error.getMessage());
        } finally {
            if (temporary != null && !temporary.delete()) temporary.deleteOnExit();
        }
        System.exit(exit);
    }

    private static java.io.InputStream openPayload(File source) throws Exception {
        java.io.BufferedInputStream input = new java.io.BufferedInputStream(new java.io.FileInputStream(source));
        try {
            java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
            int value;
            while ((value = input.read()) != -1) {
                if (value == '\n') {
                    if (line.toString("US-ASCII").equals("__FUNCTION_PAYLOAD__")) return Base64.getMimeDecoder().wrap(input);
                    line.reset();
                } else {
                    if (line.size() >= 65536) throw new java.io.IOException("脚本头部格式损坏");
                    line.write(value);
                }
            }
            throw new java.io.IOException("脚本缺少加密内容");
        } catch (Exception error) { input.close(); throw error; }
    }
}
