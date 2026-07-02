package de.verdox.solarminer.solarminerstratumproxy.v1.connection;

import de.verdox.solarminer.solarminerstratumproxy.v1.routing.ProxyProperties;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class StratumNettyServer {

    private static final Logger log = LoggerFactory.getLogger(StratumNettyServer.class);

    private final ProxyProperties proxyProperties;
    private final StratumChannelInitializer channelInitializer;

    private final EventLoopGroup bossGroup = new NioEventLoopGroup(1);
    private final EventLoopGroup workerGroup = new NioEventLoopGroup();

    private final List<ChannelFuture> serverChannels = new ArrayList<>();

    public StratumNettyServer(ProxyProperties proxyProperties, StratumChannelInitializer channelInitializer) {
        this.proxyProperties = proxyProperties;
        this.channelInitializer = channelInitializer;
    }

    @PostConstruct
    public void start() throws InterruptedException {
        ServerBootstrap b = new ServerBootstrap();
        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(channelInitializer);

        for (Map.Entry<String, ProxyProperties.CoinConfig> entry : proxyProperties.getCoins().entrySet()) {
            String algoName = entry.getKey();
            int port = entry.getValue().getPort();

            ChannelFuture f = b.bind(port).sync();
            serverChannels.add(f);

            log.info("Started Stratum V1 proxy for '{}' on port {}", algoName, port);
        }
    }

    @PreDestroy
    public void stop() {
        log.info("Shutting down Stratum V1 proxy ");
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
    }
}
