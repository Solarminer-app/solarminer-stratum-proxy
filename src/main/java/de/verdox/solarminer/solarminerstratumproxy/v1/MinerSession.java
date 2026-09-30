package de.verdox.solarminer.solarminerstratumproxy.v1;

import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeManager;
import de.verdox.solarminer.solarminerstratumproxy.v1.fee.FeeTarget;
import de.verdox.solarminer.solarminerstratumproxy.v1.protocol.MiningProtocolFactory;
import de.verdox.solarminer.solarminerstratumproxy.v1.routing.ProxyProperties;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LineBasedFrameDecoder;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Scope("prototype")
public class MinerSession implements ProxyContext {
    private static final Logger log = LoggerFactory.getLogger(MinerSession.class);

    private final ProxyProperties proxyProperties;
    private MiningProtocol miningProtocol;
    private final FeeManager feeManager;
    private final MiningProtocolFactory factory;
    @Value("${proxy.fee.required:false}")
    private boolean feeRequired;

    private String dynamicPool;
    private String dynamicWorker;
    private String dynamicPass;

    private Channel minerChannel;
    private String coinName;
    private final ConcurrentHashMap<String, Channel> upstreamChannels = new ConcurrentHashMap<>();
    private final List<String> messageBuffer = new ArrayList<>();

    private boolean isConnectedToUpstream = false;
    private String currentTargetId = FeeManager.USER_TARGET_ID;

    private String minerIp;

    public MinerSession(FeeManager feeManager, MiningProtocolFactory factory, ProxyProperties proxyProperties) {
        this.feeManager = feeManager;
        this.factory = factory;
        this.proxyProperties = proxyProperties;
    }

    public void initialize(Channel minerChannel, String coinName) {
        this.miningProtocol = factory.getProtocol(coinName);
        this.minerChannel = minerChannel;
        this.coinName = coinName;

        if (minerChannel.remoteAddress() instanceof java.net.InetSocketAddress inetAddress) {
            this.minerIp = inetAddress.getAddress().getHostAddress();
        } else {
            this.minerIp = "unknown";
        }

        log.info("New miner connection from: {}", this.minerIp+" ["+coinName+"]");
    }

    public void handleMessageFromMiner(String rawJson) {
        if (feeRequired && !feeTargetsReady()) { disconnect(); return; }
        rawJson = miningProtocol.interceptMessageFromMiner(rawJson, this);
        if (!isConnectedToUpstream && messageBuffer.size() < 16 && rawJson.length() <= 16384)
            messageBuffer.add(rawJson);
        log.debug("Miner -> Proxy: {} characters", rawJson.length());
        miningProtocol.handleMessageFromMiner(rawJson, this);
    }

    public void handleMessageFromPool(String rawJson, String targetId) {
        if (feeRequired && !feeTargetsReady()) { disconnect(); return; }
        log.debug("Pool [{}] -> Proxy: {} characters", targetId, rawJson.length());
        miningProtocol.handleMessageFromPool(rawJson, targetId, this);
    }

    @Override
    public void sendToMiner(String message) {
        log.debug("Proxy -> Miner: {} characters", message.length());
        if (minerChannel != null && minerChannel.isActive()) minerChannel.writeAndFlush(message + "\n");
    }

    @Override
    public void setDynamicRouting(String pool, String worker, String pass) {
        this.dynamicPool = pool;
        this.dynamicWorker = worker;
        this.dynamicPass = pass;
    }

    @Override
    public void sendToUpstream(String targetId, String message) {
        log.debug("Proxy -> Pool[{}]: {} characters", targetId, message.length());
        Channel targetChannel = upstreamChannels.get(targetId);
        if (targetChannel != null && targetChannel.isActive()) {
            targetChannel.writeAndFlush(message + "\n");
        }
    }

    @Override
    public void reconnectToTarget(String targetId, String newAddress) {
        Channel oldChannel = upstreamChannels.remove(targetId);
        if (oldChannel != null) {
            oldChannel.close();
        }

        connectToUpstream(targetId, newAddress, minerChannel.eventLoop(), () -> {
            log.info("Reconnect erfolgreich für Target {} auf {}", targetId, newAddress);
        });
    }

    @Override
    public void broadcastToUpstreams(String message) {
        log.debug("Proxy -> Pool: {} characters", message.length());
        for (Channel channel : upstreamChannels.values()) {
            if (channel.isActive()) {
                channel.writeAndFlush(message + "\n");
            }
        }
    }

    @Override
    public void connectToTargetPool(String workerName) {
        if (this.dynamicPool == null) {
            log.error("Connection refused: Miner IP {} did not define a target pool in the workername (Format: pool;user;pass)", this.minerIp);
            disconnect();
            return;
        }
        if (feeRequired && !feeTargetsReady()) {
            log.error("Connection rejected for {}: no usable fee target is loaded", coinName);
            disconnect();
            return;
        }

        log.info("Routing {} to {}", workerName, this.dynamicPool);

        connectToUpstream(FeeManager.USER_TARGET_ID, this.dynamicPool, minerChannel.eventLoop(), () -> {
            log.info("Main pool connection established for USER. Activating real-time routing.");
            this.isConnectedToUpstream = true;
            List<String> initialMessages = List.copyOf(messageBuffer);
            messageBuffer.clear();
            flushBufferForTarget(FeeManager.USER_TARGET_ID, initialMessages);

            for (FeeTarget target : feeManager.getFeeTargets(coinName)) {
                connectToUpstream(target.targetId(), target.poolAddress(), minerChannel.eventLoop(), () -> {
                    log.info("Dev Fee pool connection established for target: {}", target.targetId());
                    flushBufferForTarget(target.targetId(), initialMessages);
                });
            }
        });
    }

    private boolean feeTargetsReady() {
        return feeManager.getFeeTargets(coinName).stream().anyMatch(target -> target.percentage() > 0
                && target.poolAddress() != null && !target.poolAddress().isBlank()
                && target.workerName() != null && !target.workerName().isBlank());
    }

    @Override
    public void disconnect() {
        if (minerChannel != null && minerChannel.isActive()) minerChannel.close();
        upstreamChannels.values().forEach(Channel::close);
        upstreamChannels.clear();
    }

    @Override
    public boolean isConnectedToUpstream() {
        return this.isConnectedToUpstream;
    }

    @Override
    public String getCurrentTargetId() {
        return this.currentTargetId;
    }

    @Override
    public void setCurrentTargetId(String targetId) {
        if (!this.currentTargetId.equals(targetId)) {
            log.info("Switch: {} -> {}", this.currentTargetId, targetId);
            this.currentTargetId = targetId;
            miningProtocol.onTargetChanged(targetId, this);
        }
    }

    @Override
    public String rollNextJobTarget() {
        return feeManager.rollNextJobTarget(coinName);
    }

    @Override
    public String getWorkerForTarget(String targetId) {
        if (FeeManager.USER_TARGET_ID.equals(targetId) && dynamicWorker != null) return dynamicWorker;
        FeeTarget target = feeManager.getTarget(coinName, targetId);
        return target != null ? target.workerName() : null;
    }

    @Override
    public String getPasswordForTarget(String targetId) {
        if (FeeManager.USER_TARGET_ID.equals(targetId) && dynamicPass != null) return dynamicPass;
        FeeTarget target = feeManager.getTarget(coinName, targetId);
        return target != null ? target.password() : null;
    }

    private void connectToUpstream(String targetId, String address, EventLoop eventLoop, Runnable onSuccess) {
        URI upstream;
        try {
            upstream = URI.create(address.contains("://") ? address : "stratum+tcp://" + address);
        } catch (IllegalArgumentException e) {
            log.error("Invalid upstream URL for target {}", targetId);
            return;
        }
        String scheme = upstream.getScheme();
        String host = upstream.getHost();
        int port = upstream.getPort();
        if ((!"stratum+tcp".equals(scheme) && !"stratum+ssl".equals(scheme)) || host == null || port < 1 || port > 65535) {
            log.error("Unsupported upstream URL for target {}", targetId);
            return;
        }
        boolean tls = "stratum+ssl".equals(scheme);
        SslContext sslContext;
        try {
            sslContext = tls ? SslContextBuilder.forClient().endpointIdentificationAlgorithm("HTTPS").build() : null;
        } catch (javax.net.ssl.SSLException e) {
            log.error("TLS initialization failed for target {}", targetId, e);
            return;
        }
        Bootstrap bootstrap = new Bootstrap().group(eventLoop).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true);
        bootstrap.handler(createPoolInitializer(targetId, sslContext, host, port));

            ChannelFuture future = bootstrap.connect(host, port);
            future.addListener((ChannelFutureListener) f -> {
                if (f.isSuccess()) {
                    if (tls) {
                        SslHandler sslHandler = f.channel().pipeline().get(SslHandler.class);
                        sslHandler.handshakeFuture().addListener(handshake -> {
                            if (handshake.isSuccess()) {
                                upstreamChannels.put(targetId, f.channel());
                                if (onSuccess != null) onSuccess.run();
                            } else {
                                log.error("TLS handshake failed for target {}", targetId);
                                f.channel().close();
                                if (targetId.equals(FeeManager.USER_TARGET_ID)) disconnect();
                            }
                        });
                    } else {
                        upstreamChannels.put(targetId, f.channel());
                        if (onSuccess != null) onSuccess.run();
                    }
                    log.info("Successfully connected to upstream pool [{}]: {}", targetId, address);
                } else {
                    log.error("Connection to {} ({}) did not work!", targetId, address);
                    if (targetId.equals(FeeManager.USER_TARGET_ID)) disconnect();
                }
            });
    }

    private ChannelInitializer<SocketChannel> createPoolInitializer(String targetId, SslContext sslContext, String host, int port) {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                if (sslContext != null) ch.pipeline().addLast(sslContext.newHandler(ch.alloc(), host, port));
                ch.pipeline().addLast(new LineBasedFrameDecoder(4_194_304));
                ch.pipeline().addLast(new StringDecoder());
                ch.pipeline().addLast(new StringEncoder());
                ch.pipeline().addLast(new SimpleChannelInboundHandler<String>() {
                    @Override
                    protected void channelRead0(ChannelHandlerContext ctx, String msg) {
                        handleMessageFromPool(msg, targetId);
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        if (targetId.equals(FeeManager.USER_TARGET_ID)) disconnect();
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        ctx.close();
                    }
                });
            }
        };
    }

    private void flushBufferForTarget(String targetId, List<String> initialMessages) {
        log.info("Flushing handshake buffer specifically for target: {}", targetId);
        for (String rawJson : initialMessages) {
            if (FeeManager.USER_TARGET_ID.equals(targetId)) {
                sendToUpstream(FeeManager.USER_TARGET_ID, rawJson);
            } else {
                String rewritten = miningProtocol.translateMessageForUpstream(rawJson, targetId, this);
                sendToUpstream(targetId, rewritten != null ? rewritten : rawJson);
            }
        }
    }
}
