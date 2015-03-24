/*
 * Copyright 2014 CyberVision, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.kaaproject.kaa.client.channel;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.KeyPair;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;

import org.junit.Test;
import org.kaaproject.kaa.client.channel.connectivity.ConnectivityChecker;
import org.kaaproject.kaa.client.channel.impl.channels.DefaultOperationTcpChannel;
import org.kaaproject.kaa.client.persistence.KaaClientState;
import org.kaaproject.kaa.common.TransportType;
import org.kaaproject.kaa.common.avro.AvroByteArrayConverter;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.Disconnect;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.Disconnect.DisconnectReason;
import org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.PingResponse;
import org.kaaproject.kaa.common.endpoint.gen.SyncRequest;
import org.kaaproject.kaa.common.endpoint.gen.SyncResponse;
import org.kaaproject.kaa.common.endpoint.gen.SyncResponseResultType;
import org.kaaproject.kaa.common.endpoint.security.KeyUtil;
import org.mockito.Mockito;

public class DefaultOperationTcpChannelTest {

    private final KeyPair clientKeys;

    public DefaultOperationTcpChannelTest() throws Exception {
        clientKeys = KeyUtil.generateKeyPair();
    }

    class TestOperationTcpChannel extends DefaultOperationTcpChannel {
        public Socket socketMock;
        public OutputStream os;
        public InputStream is;

        public TestOperationTcpChannel(KaaClientState state,
                KaaChannelManager channelManager) throws IOException {
            super(state, channelManager);
            PipedInputStream in = new PipedInputStream(4096);
            PipedOutputStream out = new PipedOutputStream(in);
            os = out;
            is = in;
            socketMock = Mockito.mock(Socket.class);
            Mockito.when(socketMock.getOutputStream()).thenReturn(os);
            Mockito.when(socketMock.getInputStream()).thenReturn(is);
        }

        @Override
        protected Socket createSocket(String host, int port)
                throws UnknownHostException, IOException {
            return socketMock;
        }
        @Override
        public void shutdown() {
            super.shutdown();
        }
    }

    @Test
    public void testDefaultOperationTcpChannel() {
        KaaClientState clientState = Mockito.mock(KaaClientState.class);
        KaaChannelManager channelManager = Mockito.mock(KaaChannelManager.class);
        KaaDataChannel tcpChannel = new DefaultOperationTcpChannel(clientState, channelManager);
        assertNotNull("New channel's id is null", tcpChannel.getId());
        assertNotNull("New channel does not support any of transport types", tcpChannel.getSupportedTransportTypes());
        assertNotEquals(0, tcpChannel.getSupportedTransportTypes().size());
    }

    @Test
    public void testSync() throws Exception {
        KaaClientState clientState = Mockito.mock(KaaClientState.class);
        Mockito.when(clientState.getPrivateKey()).thenReturn(clientKeys.getPrivate());
        Mockito.when(clientState.getPublicKey()).thenReturn(clientKeys.getPublic());

        KaaChannelManager channelManager = Mockito.mock(KaaChannelManager.class);
        TestOperationTcpChannel tcpChannel = new TestOperationTcpChannel(clientState, channelManager);

        AvroByteArrayConverter<SyncResponse> responseCreator = new AvroByteArrayConverter<SyncResponse>(SyncResponse.class);
        AvroByteArrayConverter<SyncRequest> requestCreator = new AvroByteArrayConverter<SyncRequest>(SyncRequest.class);
        KaaDataMultiplexer multiplexer = Mockito.mock(KaaDataMultiplexer.class);
        Mockito.when(multiplexer.compileRequest(Mockito.anyMapOf(TransportType.class, ChannelDirection.class))).thenReturn(requestCreator.toByteArray(new SyncRequest()));
        KaaDataDemultiplexer demultiplexer = Mockito.mock(KaaDataDemultiplexer.class);
        tcpChannel.setMultiplexer(multiplexer);
        tcpChannel.setDemultiplexer(demultiplexer);
        tcpChannel.sync(TransportType.USER);        // will cause call to KaaDataMultiplexer.compileRequest(...) after "CONNECT" messsage
        tcpChannel.sync(TransportType.PROFILE);

        TransportConnectionInfo server = IPTransportInfoTest.createTestServerInfo(ServerType.OPERATIONS, TransportProtocolIdConstants.TCP_TRANSPORT_ID,
                "localhost", 9009, KeyUtil.generateKeyPair().getPublic());

        tcpChannel.setServer(server); // causes call to KaaDataMultiplexer.compileRequest(...) for "CONNECT" messsage
        byte [] rawConnack = new byte[] { 0x20, 0x02, 0x00, 0x01 };
        tcpChannel.os.write(rawConnack);

        SyncResponse response = new SyncResponse();
        response.setStatus(SyncResponseResultType.SUCCESS);
        tcpChannel.os.write(new org.kaaproject.kaa.common.channels.protocols.kaatcp.messages.SyncResponse(responseCreator.toByteArray(response), false, false).getFrame().array());
        tcpChannel.sync(TransportType.USER); // causes call to KaaDataMultiplexer.compileRequest(...) for "KAA_SYNC" messsage
        
        Mockito.verify(multiplexer, Mockito.times(2)).compileRequest(Mockito.anyMapOf(TransportType.class, ChannelDirection.class));

        tcpChannel.sync(TransportType.EVENT);

        Mockito.verify(multiplexer, Mockito.times(3)).compileRequest(Mockito.anyMapOf(TransportType.class, ChannelDirection.class));
        Mockito.verify(tcpChannel.socketMock, Mockito.times(3)).getOutputStream();

        Mockito.reset(multiplexer);
        tcpChannel.os.write(new PingResponse().getFrame().array());

        tcpChannel.syncAll();
        Mockito.verify(multiplexer, Mockito.times(1)).compileRequest(tcpChannel.getSupportedTransportTypes());
        tcpChannel.os.write(new Disconnect(DisconnectReason.INTERNAL_ERROR).getFrame().array());

        tcpChannel.syncAll();
        Mockito.verify(multiplexer, Mockito.times(1)).compileRequest(tcpChannel.getSupportedTransportTypes());
        tcpChannel.shutdown();
    }

    @Test
    public void testConnectivity() throws NoSuchAlgorithmException {
        KaaClientState clientState = Mockito.mock(KaaClientState.class);
        Mockito.when(clientState.getPrivateKey()).thenReturn(clientKeys.getPrivate());
        Mockito.when(clientState.getPublicKey()).thenReturn(clientKeys.getPublic());

        KaaChannelManager channelManager = Mockito.mock(KaaChannelManager.class);
        DefaultOperationTcpChannel channel = new DefaultOperationTcpChannel(clientState, channelManager);

        TransportConnectionInfo server = IPTransportInfoTest.createTestServerInfo(ServerType.OPERATIONS, TransportProtocolIdConstants.TCP_TRANSPORT_ID,
                "www.test.fake", 999, KeyUtil.generateKeyPair().getPublic());
        
        ConnectivityChecker checker = Mockito.mock(ConnectivityChecker.class);
        Mockito.when(checker.checkConnectivity()).thenReturn(false);

        channel.setConnectivityChecker(checker);
        channel.setServer(server);

        Mockito.verify(channelManager, Mockito.times(0)).onServerFailed(Mockito.any(TransportConnectionInfo.class));
    }
}
