/*
 * Copyright (c) 2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cometd.client;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;
import org.codehaus.jackson.JsonProcessingException;
import org.codehaus.jackson.Version;
import org.codehaus.jackson.map.DeserializationContext;
import org.codehaus.jackson.map.JsonSerializer;
import org.codehaus.jackson.map.SerializerProvider;
import org.codehaus.jackson.map.deser.std.UntypedObjectDeserializer;
import org.codehaus.jackson.map.module.SimpleModule;
import org.cometd.bayeux.Message;
import org.cometd.bayeux.client.ClientSessionChannel;
import org.cometd.bayeux.server.LocalSession;
import org.cometd.client.transport.ClientTransport;
import org.cometd.client.transport.LongPollingTransport;
import org.cometd.common.JacksonJSONContextClient;
import org.cometd.common.JettyJSONContextClient;
import org.cometd.server.BayeuxServerImpl;
import org.cometd.server.JacksonJSONContextServer;
import org.cometd.server.JettyJSONContextServer;
import org.cometd.server.transport.HttpTransport;
import org.eclipse.jetty.util.ajax.JSON;
import org.junit.Assert;
import org.junit.Test;

public class CustomSerializationTest extends ClientServerTest
{
    @Test
    public void testJettyCustomSerialization() throws Exception
    {
        Map<String, String> serverOptions = new HashMap<String, String>();
        serverOptions.put(BayeuxServerImpl.JSON_CONTEXT, TestJettyJSONContextServer.class.getName());
        Map<String, Object> clientOptions = new HashMap<String, Object>();
        clientOptions.put(ClientTransport.JSON_CONTEXT, new TestJettyJSONContextClient());
        testCustomSerialization(serverOptions, clientOptions);
    }

    @Test
    public void testJacksonCustomSerialization() throws Exception
    {
        Map<String, String> serverOptions = new HashMap<String, String>();
        serverOptions.put(BayeuxServerImpl.JSON_CONTEXT, TestJacksonJSONContextServer.class.getName());
        serverOptions.put(HttpTransport.JSON_DEBUG_OPTION, "true");
        Map<String, Object> clientOptions = new HashMap<String, Object>();
        clientOptions.put(ClientTransport.JSON_CONTEXT, new TestJacksonJSONContextClient());
        testCustomSerialization(serverOptions, clientOptions);
    }

    private void testCustomSerialization(Map<String, String> serverOptions, Map<String, Object> clientOptions) throws Exception
    {
        startServer(serverOptions);

        String channelName = "/foo";
        final String content = "random";
        final CountDownLatch dataLatch = new CountDownLatch(1);

        LocalSession service = bayeux.newLocalSession("custom_serialization");
        service.handshake();
        service.getChannel(channelName).subscribe(new ClientSessionChannel.MessageListener()
        {
            public void onMessage(ClientSessionChannel channel, Message message)
            {
                Data data = (Data)message.getData();
                Assert.assertEquals(content, data.content);
                dataLatch.countDown();
            }
        });

        BayeuxClient client = new BayeuxClient(cometdURL, new LongPollingTransport(clientOptions, httpClient));
        client.setDebugEnabled(debugTests());

        client.handshake();
        Assert.assertTrue(client.waitFor(5000, BayeuxClient.State.CONNECTED));
        // Wait for the connect to establish
        Thread.sleep(1000);

        client.getChannel(channelName).publish(new Data(content));
        Assert.assertTrue(dataLatch.await(5, TimeUnit.SECONDS));

        disconnectBayeuxClient(client);
    }

    private static class TestJettyJSONContextClient extends JettyJSONContextClient
    {
        private TestJettyJSONContextClient()
        {
            getJSON().addConvertor(Data.class, new DataConvertor());
        }
    }

    public static class TestJettyJSONContextServer extends JettyJSONContextServer
    {
        public TestJettyJSONContextServer()
        {
            getJSON().addConvertor(Data.class, new DataConvertor());
        }
    }

    public static class TestJacksonJSONContextServer extends JacksonJSONContextServer
    {
        public TestJacksonJSONContextServer()
        {
            SimpleModule module = new SimpleModule("test", Version.unknownVersion());
            module.addSerializer(Data.class, new DataSerializer());
            module.addDeserializer(Object.class, new DataDeserializer());
            getObjectMapper().registerModule(module);
        }
    }

    public static class TestJacksonJSONContextClient extends JacksonJSONContextClient
    {
        public TestJacksonJSONContextClient()
        {
            SimpleModule module = new SimpleModule("test", Version.unknownVersion());
            module.addSerializer(Data.class, new DataSerializer());
            module.addDeserializer(Object.class, new DataDeserializer());
            getObjectMapper().registerModule(module);
        }
    }

    private static class Data
    {
        private String content;

        public Data(String content)
        {
            this.content = content;
        }
    }

    private static class DataConvertor implements JSON.Convertor
    {
        public void toJSON(Object object, JSON.Output output)
        {
            Data data = (Data)object;
            output.addClass(Data.class);
            output.add("content", data.content);
        }

        public Object fromJSON(Map map)
        {
            String content = (String)map.get("content");
            return new Data(content);
        }
    }

    private static class DataSerializer extends JsonSerializer<Data>
    {
        @Override
        public void serialize(Data data, JsonGenerator jsonGenerator, SerializerProvider provider) throws IOException, JsonProcessingException
        {
            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField("class", Data.class.getName());
            jsonGenerator.writeStringField("content", data.content);
            jsonGenerator.writeEndObject();
        }
    }

    private static class DataDeserializer extends UntypedObjectDeserializer
    {
        @Override
        public Object deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException, JsonProcessingException
        {
            Object object = super.deserialize(jsonParser, context);
            if (object instanceof Map)
            {
                Map<String, Object> map = (Map<String, Object>)object;
                if (Data.class.getName().equals(map.get("class")))
                    return new Data((String)map.get("content"));
            }
            return object;
        }
    }
}
