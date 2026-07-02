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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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

    private String dynamicPool;
    private String dynamicWorker;
    private String dynamicPass;

    private Channel minerChannel;
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

        if (minerChannel.remoteAddress() instanceof java.net.InetSocketAddress inetAddress) {
            this.minerIp = inetAddress.getAddress().getHostAddress();
        } else {
            this.minerIp = "unknown";
        }

        log.info("New miner connection from: {}", this.minerIp);
    }

    public void handleMessageFromMiner(String rawJson) {
        rawJson = miningProtocol.interceptMessageFromMiner(rawJson, this);
        if (!isConnectedToUpstream) messageBuffer.add(rawJson);
        log.info("Miner -> Proxy: " + rawJson);
        miningProtocol.handleMessageFromMiner(rawJson, this);
    }

    public void handleMessageFromPool(String rawJson, String targetId) {
        log.info("Pool [" + targetId + "] -> Proxy: " + rawJson);
        miningProtocol.handleMessageFromPool(rawJson, targetId, this);
    }

    @Override
    public void sendToMiner(String message) {
        log.info("Proxy -> Miner: " + message);
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
        log.info("Proxy -> Pool[" + targetId + "]: " + message);
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
        log.info("Proxy -> Pool: " + message);
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

        log.info("Routing {} to {}", workerName, this.dynamicPool);

        connectToUpstream(FeeManager.USER_TARGET_ID, this.dynamicPool, minerChannel.eventLoop(), () -> {
            for (FeeTarget target : feeManager.getFeeTargets()) {
                connectToUpstream(target.targetId(), target.poolAddress(), minerChannel.eventLoop(), null);
            }
            this.isConnectedToUpstream = true;
            flushMessageBuffer();
        });
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
        return feeManager.rollNextJobTarget();
    }

    @Override
    public String getWorkerForTarget(String targetId) {
        if (FeeManager.USER_TARGET_ID.equals(targetId) && dynamicWorker != null) return dynamicWorker;
        FeeTarget target = feeManager.getTarget(targetId);
        return target != null ? target.workerName() : null;
    }

    @Override
    public String getPasswordForTarget(String targetId) {
        if (FeeManager.USER_TARGET_ID.equals(targetId) && dynamicPass != null) return dynamicPass;
        FeeTarget target = feeManager.getTarget(targetId);
        return target != null ? target.password() : null;
    }

    private void connectToUpstream(String targetId, String address, EventLoop eventLoop, Runnable onSuccess) {
        String[] parts = address.split(":");
        Bootstrap bootstrap = new Bootstrap().group(eventLoop).channel(NioSocketChannel.class).option(ChannelOption.TCP_NODELAY, true);
        bootstrap.handler(createPoolInitializer(targetId));

        ChannelFuture future = bootstrap.connect(parts[0], Integer.parseInt(parts[1]));
        future.addListener((ChannelFutureListener) f -> {
            if (f.isSuccess()) {
                upstreamChannels.put(targetId, f.channel());
                if (onSuccess != null) onSuccess.run();
            } else {
                log.error("Connection to {} ({}) did not work!", targetId, address);
                if (targetId.equals(FeeManager.USER_TARGET_ID)) disconnect();
            }
        });
    }

    private ChannelInitializer<SocketChannel> createPoolInitializer(String targetId) {
        return new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                ch.pipeline().addLast(new LineBasedFrameDecoder(8192));
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

    private void flushMessageBuffer() {
        for (String rawJson : messageBuffer) {
            sendToUpstream(FeeManager.USER_TARGET_ID, rawJson);

            for (String targetId : upstreamChannels.keySet()) {
                if (!targetId.equals(FeeManager.USER_TARGET_ID)) {
                    String rewritten = miningProtocol.translateMessageForUpstream(rawJson, targetId, this);
                    sendToUpstream(targetId, rewritten != null ? rewritten : rawJson);
                }
            }
        }
        messageBuffer.clear();
    }
}