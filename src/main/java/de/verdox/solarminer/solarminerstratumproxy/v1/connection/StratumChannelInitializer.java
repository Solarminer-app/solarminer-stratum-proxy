package de.verdox.solarminer.solarminerstratumproxy.v1.connection;

import de.verdox.solarminer.solarminerstratumproxy.v1.routing.ProxyProperties;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StratumChannelInitializer extends ChannelInitializer<SocketChannel> {

    private final MinerSessionManager sessionManager;
    private final ProxyProperties proxyProperties;

    public StratumChannelInitializer(MinerSessionManager sessionManager, ProxyProperties proxyProperties) {
        this.sessionManager = sessionManager;
        this.proxyProperties = proxyProperties;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        int localPort = ch.localAddress().getPort();
        String coinName = determineCoinNameByPort(localPort);
        ch.pipeline().addLast(new LineBasedFrameDecoder(8192));
        ch.pipeline().addLast(new StringDecoder());
        ch.pipeline().addLast(new StringEncoder());
        ch.pipeline().addLast(new MinerInboundHandler(sessionManager, coinName));
    }

    private String determineCoinNameByPort(int port) {
        for (Map.Entry<String, ProxyProperties.CoinConfig> entry : proxyProperties.getCoins().entrySet()) {
            if (entry.getValue().getPort() == port) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("Unknown port: " + port);
    }
}