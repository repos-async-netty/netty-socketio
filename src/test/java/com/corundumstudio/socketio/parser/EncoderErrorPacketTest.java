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
package com.corundumstudio.socketio.parser;

import java.io.IOException;

import org.codehaus.jackson.map.ObjectMapper;
import org.junit.Assert;
import org.junit.Test;

public class EncoderErrorPacketTest {

    private Encoder encoder = new Encoder(new ObjectMapper());

    @Test
    public void testEncode() throws IOException {
        Packet packet = new Packet(PacketType.ERROR);
        String result = encoder.encodePacket(packet);
        Assert.assertEquals("7::", result);
    }

    @Test
    public void testEncodeWithReason() throws IOException {
        Packet packet = new Packet(PacketType.ERROR);
        packet.setReason(ErrorReason.TRANSPORT_NOT_SUPPORTED);
        String result = encoder.encodePacket(packet);
        Assert.assertEquals("7:::0", result);
    }

    @Test
    public void testEncodeWithReasonAndAdvice() throws IOException {
        Packet packet = new Packet(PacketType.ERROR);
        packet.setReason(ErrorReason.UNAUTHORIZED);
        packet.setAdvice(ErrorAdvice.RECONNECT);
        String result = encoder.encodePacket(packet);
        Assert.assertEquals("7:::2+0", result);
    }

    @Test
    public void testEncodeWithEndpoint() throws IOException {
        Packet packet = new Packet(PacketType.ERROR);
        packet.setEndpoint("/woot");
        String result = encoder.encodePacket(packet);
        Assert.assertEquals("7::/woot", result);
    }
}
