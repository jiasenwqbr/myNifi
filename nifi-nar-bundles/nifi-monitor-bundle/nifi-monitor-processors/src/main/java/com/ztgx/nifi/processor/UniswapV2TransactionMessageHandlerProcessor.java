package com.ztgx.nifi.processor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.InputStreamCallback;
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.stream.io.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.*;

@SideEffectFree
@Tags({"uniswapv2", "transaction handler"})
@CapabilityDescription("transaction handler")
public class UniswapV2TransactionMessageHandlerProcessor extends AbstractProcessor {

    private final Set<Relationship> relationships;
    public static final Relationship REL_TRANSACTION = new Relationship.Builder()
            .description("transaction")
            .name("transaction")
            .build();
    public static final Relationship REL_SWAP = new Relationship.Builder()
            .description("swap")
            .name("swap")
            .build();
    public static final Relationship REL_PAIR = new Relationship.Builder()
            .description("pair")
            .name("pair")
            .build();
    public static final Relationship REL_FAILURE = new Relationship.Builder()
            .name("failure")
            .description("failure")
            .build();

    public UniswapV2TransactionMessageHandlerProcessor() {
        final Set<Relationship> relationshipSet = new HashSet<>();
        relationshipSet.add(REL_TRANSACTION);
        relationshipSet.add(REL_SWAP);
        relationshipSet.add(REL_PAIR);
        relationshipSet.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(relationshipSet);
    }

    public Set<Relationship> getRelationships() {
        return this.relationships;
    }
    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        FlowFile flowFile = session.get();
        if (flowFile == null) {
            return;
        }
        final byte[] content = new byte[(int) flowFile.getSize()];
        session.read(flowFile, new InputStreamCallback() {
            @Override
            public void process(final InputStream in) throws IOException {
                StreamUtils.fillBuffer(in, content, true);
            }
        });
        final Charset charset = Charset.forName(StandardCharsets.UTF_8.name());
        String data = new String(content, charset);
        JSONObject transaction = JSON.parseObject(data);
        String id = transaction.getString("id");
        String blockNumber = transaction.getString("blockNumber");
        String timestamp = transaction.getString("timestamp");
        JSONObject trans = new JSONObject();
        trans.put("id", id);
        trans.put("blockNumber", blockNumber);
        trans.put("timestamp", timestamp);

        JSONArray swaps = transaction.getJSONArray("swaps");
        JSONArray pairs = new JSONArray();
        if (!swaps.isEmpty()){
            for (int i = 0; i < swaps.size(); i++) {
                JSONObject swap = swaps.getJSONObject(i);
                swap.put("transactionId", id);
                JSONObject pair = swap.getJSONObject("pair");
                pair.put("transactionId", id);
                pair.put("swapId", swap.getString("id"));
                pairs.add(pair);
            }
        }



        Map<String, String> attributes = new HashMap<String, String>();
        attributes.put(CoreAttributes.MIME_TYPE.key(), "application/json");
        // transaction
        // flowFile = session.create();
        flowFile = session.write(flowFile, new StreamCallback() {
            @Override
            public void process(InputStream in, OutputStream out) throws IOException
            {
                out.write(JSONArray.toJSONString(trans, SerializerFeature.WriteMapNullValue)
                        .getBytes(StandardCharsets.UTF_8));
            }
        });

        flowFile = session.putAllAttributes(flowFile,attributes);
        session.transfer(flowFile, REL_TRANSACTION);

        // swaps
        if (!swaps.isEmpty()){
            for (int i = 0; i < swaps.size(); i++) {
                JSONObject swap = swaps.getJSONObject(i);
                flowFile = session.create();
                flowFile = session.write(flowFile, new StreamCallback() {
                    @Override
                    public void process(InputStream in, OutputStream out) throws IOException
                    {
                        out.write(JSONArray.toJSONString(swap, SerializerFeature.WriteMapNullValue)
                                .getBytes(StandardCharsets.UTF_8));
                    }
                });
                flowFile = session.putAllAttributes(flowFile,attributes);
                session.transfer(flowFile, REL_SWAP);
            }
        }



        // pairs
        if (!pairs.isEmpty()){
            for (int i = 0; i < pairs.size(); i++) {
                JSONObject pair = pairs.getJSONObject(i);
                flowFile = session.create();
                flowFile = session.write(flowFile, new StreamCallback() {
                    @Override
                    public void process(InputStream in, OutputStream out) throws IOException
                    {
                        out.write(JSONArray.toJSONString(pair, SerializerFeature.WriteMapNullValue)
                                .getBytes(StandardCharsets.UTF_8));
                    }
                });
                flowFile = session.putAllAttributes(flowFile,attributes);
                session.transfer(flowFile, REL_PAIR);
            }
        }

    }
}
