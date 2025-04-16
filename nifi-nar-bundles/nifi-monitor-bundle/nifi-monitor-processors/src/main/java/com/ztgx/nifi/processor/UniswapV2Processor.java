package com.ztgx.nifi.processor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.alibaba.fastjson.serializer.SerializerFeature;
import com.ztgx.nifi.util.GraphQLUtil;
import com.ztgx.nifi.util.JedisUtil;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.dbcp.DBCPService;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.flowfile.attributes.CoreAttributes;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

@SideEffectFree
@Tags({"uniswapv2", "sync"})
@CapabilityDescription("uniswapv2数据同步抓取")
public class UniswapV2Processor  extends AbstractProcessor {
    private final Set<Relationship> relationships;
    protected List<PropertyDescriptor> propDescriptors;
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .description("数据转换成功。")
            .name("成功")
            .build();
    public static final Relationship REL_FAILURE = new Relationship.Builder().description("数据转换失败").name("失败").build();



    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    public static final PropertyDescriptor JSON_TEMPLATE = new PropertyDescriptor.Builder()
            .name("JSON_TEMPLATE")
            .displayName("json template")
            .description("模版.")
            .defaultValue("")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();

    public static final PropertyDescriptor REDIS_IP = new PropertyDescriptor.Builder()
            .name("REDIS_IP")
            .displayName("redis ip address")
            .description("redis ip address")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();
    public static final PropertyDescriptor REDIS_PORT = new PropertyDescriptor.Builder()
            .name("REDIS_PORT")
            .displayName("redis port")
            .description("redis port")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();
    public static final PropertyDescriptor REDIS_PWD = new PropertyDescriptor.Builder()
            .name("REDIS_PWD")
            .displayName("redis password")
            .description("redis password")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();
    public static final PropertyDescriptor BLOCK_NUMBER = new PropertyDescriptor.Builder()
            .name("BLOCK_NUMBER")
            .displayName("block number")
            .description("block number")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();

    public static final PropertyDescriptor GRAP_URL = new PropertyDescriptor.Builder()
            .name("GRAP_URL")
            .displayName("The graph url")
            .description("The graph url")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();


    public UniswapV2Processor() {
        final Set<Relationship> relationshipSet = new HashSet<>();
        relationshipSet.add(REL_SUCCESS);
        relationshipSet.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(relationshipSet);
        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(JSON_TEMPLATE);
        pds.add(REDIS_IP);
        pds.add(REDIS_PORT);
        pds.add(REDIS_PWD);
        pds.add(BLOCK_NUMBER);
        pds.add(GRAP_URL);
        propDescriptors = Collections.unmodifiableList(pds);
    }



    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propDescriptors;
    }


    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        PropertyValue property = context.getProperty(JSON_TEMPLATE);
        String template = property.getValue();
        String redisIp = context.getProperty(REDIS_IP).getValue();
        String redisPort = context.getProperty(REDIS_PORT).getValue();
        String redisPwd = context.getProperty(REDIS_PWD).getValue();
        String blockNumber = context.getProperty(BLOCK_NUMBER).getValue();
        String graphUrl = context.getProperty(GRAP_URL).getValue();
        Jedis jedis = JedisUtil.getJedis(redisIp, Integer.parseInt(redisPort), redisPwd);
        String currentBlockNumber = jedis.get("uniSwapV2CurrentBlockNumber");
        if (currentBlockNumber==null){
            currentBlockNumber = blockNumber;
            jedis.set("uniSwapV2CurrentBlockNumber",currentBlockNumber);
        }
        String newBody = template.replace("<<<blockNumber>>>",currentBlockNumber);
        JSONObject jsonObject = JSON.parseObject(newBody);

        // do some process
        FlowFile flowFile = session.create();

        String post = GraphQLUtil.post(graphUrl, "application/json", newBody);

        boolean isBlocked = isBlocked(post,currentBlockNumber);
        if (isBlocked) {
            flowFile = session.write(flowFile, new StreamCallback() {
                @Override
                public void process(InputStream in, OutputStream out) throws IOException {
                    out.write(post.getBytes(StandardCharsets.UTF_8));
                }
            });
            Map<String, String> generatedAttributes = new HashMap<String, String>();
            generatedAttributes.put(CoreAttributes.MIME_TYPE.key(), "application/json");
            flowFile = session.putAllAttributes(flowFile, generatedAttributes);
            if (Long.parseLong(currentBlockNumber)>10000834){
                Long current = Long.parseLong(currentBlockNumber)-1;
                jedis.set("uniSwapV2CurrentBlockNumber",String.valueOf(current));
            }
            if (Long.parseLong(currentBlockNumber)<=10000834){
                jedis.set("uniSwapV2CurrentBlockNumber",blockNumber);
            }
            session.transfer(flowFile,REL_SUCCESS);
        } else {
            flowFile = session.write(flowFile, new StreamCallback() {
                @Override
                public void process(InputStream in, OutputStream out) throws IOException {
                    out.write(post.getBytes(StandardCharsets.UTF_8));
                }
            });
            Map<String, String> generatedAttributes = new HashMap<String, String>();
            generatedAttributes.put(CoreAttributes.MIME_TYPE.key(), "application/json");
            flowFile = session.putAllAttributes(flowFile, generatedAttributes);

            if (Long.parseLong(currentBlockNumber)>10000834){
                Long current = Long.parseLong(currentBlockNumber)-1;
                jedis.set("uniSwapV2CurrentBlockNumber",String.valueOf(current));
            }
            if (Long.parseLong(currentBlockNumber)<=10000834){
                jedis.set("uniSwapV2CurrentBlockNumber",blockNumber);
            }
            session.transfer(flowFile,REL_FAILURE);
        }
    }

    private boolean isBlocked(String post,String currentBlockNumber) {
        boolean isBlocked = false;
        JSONObject jsonObject = JSON.parseObject(post);
        if (jsonObject!=null && !jsonObject.isEmpty()){
            if (jsonObject.containsKey("data")){
                JSONObject data = jsonObject.getJSONObject("data");
                if (data!=null){
                    JSONArray transactions = data.getJSONArray("transactions");
                    if (!transactions.isEmpty()){
                        isBlocked = true;
                    }
                }
            }else {
                System.out.println("jsonObject.containsKey(data)==boolean:"+jsonObject.toJSONString()+"........currentBlockNumber:"+currentBlockNumber);
            }
        }else {
            System.out.println("jsonObject.isEmpty()==true:"+post+"........currentBlockNumber:"+currentBlockNumber);
        }


        return isBlocked;
    }
}
