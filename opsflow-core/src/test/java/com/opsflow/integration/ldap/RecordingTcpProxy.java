package com.opsflow.integration.ldap;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 测试用 TCP 转发代理：把客户端发往服务器的原始字节记下来，用来检查密码有没有以明文出现在网络上。
 */
final class RecordingTcpProxy implements AutoCloseable {

    private final ServerSocket serverSocket;
    private final int targetPort;
    private final ByteArrayOutputStream clientToServer = new ByteArrayOutputStream();
    private final Thread acceptThread;

    RecordingTcpProxy(int targetPort) throws IOException {
        this.targetPort = targetPort;
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.acceptThread = new Thread(this::acceptLoop, "ldap-test-proxy");
        this.acceptThread.setDaemon(true);
        this.acceptThread.start();
    }

    int port() {
        return serverSocket.getLocalPort();
    }

    /** 客户端发出的全部字节（按 ISO-8859-1 原样转成字符串，便于查找） */
    String clientBytes() {
        synchronized (clientToServer) {
            return new String(clientToServer.toByteArray(), StandardCharsets.ISO_8859_1);
        }
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            try {
                Socket client = serverSocket.accept();
                Socket upstream = new Socket(InetAddress.getLoopbackAddress(), targetPort);
                pump(client.getInputStream(), upstream.getOutputStream(), true, client, upstream);
                pump(upstream.getInputStream(), client.getOutputStream(), false, client, upstream);
            } catch (IOException e) {
                return;
            }
        }
    }

    private void pump(InputStream in, OutputStream out, boolean record, Socket a, Socket b) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            try {
                int n;
                while ((n = in.read(buf)) >= 0) {
                    if (record) {
                        synchronized (clientToServer) {
                            clientToServer.write(buf, 0, n);
                        }
                    }
                    out.write(buf, 0, n);
                    out.flush();
                }
            } catch (IOException ignored) {
                // 连接关闭
            } finally {
                closeQuietly(a);
                closeQuietly(b);
            }
        }, "ldap-test-proxy-pump");
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // ignore
        }
    }

    @Override
    public void close() throws IOException {
        serverSocket.close();
    }
}
