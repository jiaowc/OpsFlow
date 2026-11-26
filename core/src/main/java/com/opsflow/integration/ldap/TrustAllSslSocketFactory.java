package com.opsflow.integration.ldap;

import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

/**
 * 不校验证书的 SSLSocketFactory，只在管理员开启「跳过证书校验」时使用（有中间人风险）。
 * <p>JNDI 通过类名加载 socket 工厂，并调用其静态 {@code getDefault()}，所以这里必须是 public 类。
 */
public class TrustAllSslSocketFactory extends SSLSocketFactory {

    private static final TrustAllSslSocketFactory INSTANCE = new TrustAllSslSocketFactory();

    private final SSLSocketFactory delegate;

    public TrustAllSslSocketFactory() {
        try {
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new TrustAllManager()}, new SecureRandom());
            delegate = ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 TLS 失败", e);
        }
    }

    public static SocketFactory getDefault() {
        return INSTANCE;
    }

    public static SSLSocketFactory instance() {
        return INSTANCE;
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return delegate.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return delegate.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket() throws IOException {
        return delegate.createSocket();
    }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(s, host, port, autoClose);
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
        return delegate.createSocket(host, port, localHost, localPort);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return delegate.createSocket(host, port);
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort) throws IOException {
        return delegate.createSocket(address, port, localAddress, localPort);
    }

    /** 用 X509ExtendedTrustManager，否则 JDK 会包一层并照样做主机名校验 */
    private static final class TrustAllManager extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // 跳过校验
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
            // 跳过校验
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // 跳过校验
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
            // 跳过校验
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
            // 跳过校验
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
            // 跳过校验
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
