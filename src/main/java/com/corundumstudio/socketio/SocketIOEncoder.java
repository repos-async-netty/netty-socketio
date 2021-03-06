/**
 * Copyright 2012 Nikita Koksharov
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.corundumstudio.socketio;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONNECTION;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.CONTENT_TYPE;
import static org.jboss.netty.handler.codec.http.HttpHeaders.Values.KEEP_ALIVE;
import static org.jboss.netty.handler.codec.http.HttpVersion.HTTP_1_1;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.codehaus.jackson.map.ObjectMapper;
import org.jboss.netty.buffer.ChannelBuffers;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelFuture;
import org.jboss.netty.channel.ChannelFutureListener;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelHandler.Sharable;
import org.jboss.netty.handler.codec.http.DefaultHttpResponse;
import org.jboss.netty.handler.codec.http.HttpHeaders;
import org.jboss.netty.handler.codec.http.HttpResponse;
import org.jboss.netty.handler.codec.http.HttpResponseStatus;
import org.jboss.netty.handler.codec.http.HttpVersion;
import org.jboss.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.jboss.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.jboss.netty.handler.codec.oneone.OneToOneEncoder;
import org.jboss.netty.util.CharsetUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.corundumstudio.socketio.messages.AuthorizeMessage;
import com.corundumstudio.socketio.messages.BaseMessage;
import com.corundumstudio.socketio.messages.WebSocketPacketMessage;
import com.corundumstudio.socketio.messages.WebsocketErrorMessage;
import com.corundumstudio.socketio.messages.XHRErrorMessage;
import com.corundumstudio.socketio.messages.XHRNewChannelMessage;
import com.corundumstudio.socketio.messages.XHRPacketMessage;
import com.corundumstudio.socketio.messages.XHRPostMessage;
import com.corundumstudio.socketio.parser.Encoder;
import com.corundumstudio.socketio.parser.Packet;

@Sharable
public class SocketIOEncoder extends OneToOneEncoder implements MessageHandler {

    class XHRClientEntry {

        // AtomicInteger works faster than locking
        final AtomicInteger lastChannelId = new AtomicInteger();
        final Queue<Packet> packets = new ConcurrentLinkedQueue<Packet>();

        public void addPacket(Packet packet) {
            packets.add(packet);
        }

        public Packet pollPacket() {
            return packets.poll();
        }

        public boolean hasPackets() {
            return !packets.isEmpty();
        }

        /**
         * We can write to channel only once.
         *
         * @param channel
         * @return true - can write
         */
        public boolean tryToWrite(Channel channel) {
            int prevVal = lastChannelId.get();
            return prevVal != channel.getId()
                            && lastChannelId.compareAndSet(prevVal, channel.getId());
        }

    }

    private final Logger log = LoggerFactory.getLogger(getClass());

    private ObjectMapper objectMapper;
    private Encoder encoder;

    private ConcurrentMap<UUID, XHRClientEntry> sessionId2ActiveChannelId = new ConcurrentHashMap<UUID, XHRClientEntry>();

    public SocketIOEncoder(ObjectMapper objectMapper, Encoder encoder) {
        super();
        this.objectMapper = objectMapper;
        this.encoder = encoder;
    }

    private XHRClientEntry getXHRClientEntry(Channel channel, UUID sessionId) {
        XHRClientEntry clientEntry = sessionId2ActiveChannelId.get(sessionId);
        if (clientEntry == null) {
            clientEntry = new XHRClientEntry();
            XHRClientEntry old = sessionId2ActiveChannelId.putIfAbsent(sessionId, clientEntry);
            if (old != null) {
                clientEntry = old;
            }
        }
        return clientEntry;
    }

    private void sendPostResponse(Channel channel, String origin) {
        HttpResponse res = new DefaultHttpResponse(HttpVersion.HTTP_1_1,
                HttpResponseStatus.OK);
        if (origin != null) {
            res.addHeader("Access-Control-Allow-Origin", origin);
            res.addHeader("Access-Control-Allow-Credentials", "true");
        }

        ChannelFuture f = channel.write(res);
        f.addListener(ChannelFutureListener.CLOSE);
    }

    private void write(UUID sessionId, String origin, XHRClientEntry clientEntry,
            Channel channel) throws IOException {
        if (!channel.isConnected() || !clientEntry.hasPackets()
        			|| !clientEntry.tryToWrite(channel)) {
        	return;
        }

        List<Packet> packets = new ArrayList<Packet>();
        while (true) {
            Packet packet = clientEntry.pollPacket();
            if (packet != null) {
                packets.add(packet);
            } else {
                break;
            }
        }

        String message = encoder.encodePackets(packets);
        sendMessage(origin, sessionId, channel, message);
    }

    private void sendMessage(String origin, UUID sessionId, Channel channel,
            String message) {
        HttpResponse res = new DefaultHttpResponse(HTTP_1_1,
                HttpResponseStatus.OK);
        addHeaders(origin, res);
        res.setContent(ChannelBuffers.copiedBuffer(message, CharsetUtil.UTF_8));
        HttpHeaders.setContentLength(res, res.getContent().readableBytes());

        log.trace("Out message: {}, sessionId: {}, channelId: {}", new Object[] { message,
                sessionId, channel.getId() });
        ChannelFuture f = channel.write(res);
        f.addListener(ChannelFutureListener.CLOSE);
    }

    private void addHeaders(String origin, HttpResponse res) {
        res.addHeader(CONTENT_TYPE, "text/plain; charset=UTF-8");
        res.addHeader(CONNECTION, KEEP_ALIVE);
        if (origin != null) {
            res.addHeader("Access-Control-Allow-Origin", origin);
            res.addHeader("Access-Control-Allow-Credentials", "true");
        }
    }

    @Override
    protected Object encode(ChannelHandlerContext ctx, Channel channel, Object msg) throws Exception {
        if (msg instanceof BaseMessage) {
            BaseMessage message = (BaseMessage) msg;
            Object result = message.handleMessage(this, channel);
            if (result != null) {
                return result;
            }
        }
        return msg;
    }

    @Override
    public Object handle(XHRNewChannelMessage xhrNewChannelMessage, Channel channel) throws IOException {
        XHRClientEntry clientEntry = getXHRClientEntry(channel, xhrNewChannelMessage.getSessionId());

        write(xhrNewChannelMessage.getSessionId(), xhrNewChannelMessage.getOrigin(), clientEntry, channel);
        return ChannelBuffers.EMPTY_BUFFER;
    }

    @Override
    public Object handle(XHRPacketMessage xhrPacketMessage, Channel channel) throws IOException {
        XHRClientEntry clientEntry = getXHRClientEntry(channel, xhrPacketMessage.getSessionId());
        clientEntry.addPacket(xhrPacketMessage.getPacket());

        write(xhrPacketMessage.getSessionId(), xhrPacketMessage.getOrigin(), clientEntry, channel);
        return ChannelBuffers.EMPTY_BUFFER;
    }

    @Override
    public Object handle(XHRPostMessage xhrPostMessage, Channel channel) {
        sendPostResponse(channel, xhrPostMessage.getOrigin());
        return ChannelBuffers.EMPTY_BUFFER;
    }

    @Override
    public Object handle(AuthorizeMessage authMsg, Channel channel) throws IOException {
        String message = authMsg.getMsg();
        if (authMsg.getJsonpParam() != null) {
            message = "io.j[" + authMsg.getJsonpParam() + "]("
                    + objectMapper.writeValueAsString(message) + ");";
        }
        sendMessage(authMsg.getOrigin(), authMsg.getSessionId(), channel, message);
        return ChannelBuffers.EMPTY_BUFFER;
    }

    @Override
    public Object handle(WebSocketPacketMessage webSocketPacketMessage, Channel channel) throws IOException {
        String message = encoder.encodePacket(webSocketPacketMessage.getPacket());
        WebSocketFrame res = new TextWebSocketFrame(message.toString());
        log.trace("Out message: {} sessionId: {}", new Object[] {
                message, webSocketPacketMessage.getSessionId() });
        return res;
    }

    @Override
    public Object handle(WebsocketErrorMessage websocketErrorMessage, Channel channel) throws IOException {
        String message = encoder.encodePacket(websocketErrorMessage.getPacket());
        return new TextWebSocketFrame(message.toString());
    }

    @Override
    public Object handle(XHRErrorMessage xhrErrorMessage, Channel channel) throws IOException {
        String message = encoder.encodePacket(xhrErrorMessage.getPacket());
        sendMessage(xhrErrorMessage.getOrigin(), null, channel, message);
        return ChannelBuffers.EMPTY_BUFFER;
    }

}
