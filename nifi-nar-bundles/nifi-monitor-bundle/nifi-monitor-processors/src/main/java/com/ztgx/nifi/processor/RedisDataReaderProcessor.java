package com.ztgx.nifi.processor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ztgx.nifi.processor.entity.RedisReadEntity;
import com.ztgx.nifi.util.GraphQLUtil;
import com.ztgx.nifi.util.JedisUtil;
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
import org.apache.nifi.processor.io.StreamCallback;
import org.apache.nifi.processor.util.StandardValidators;
import redis.clients.jedis.Jedis;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@SideEffectFree
@Tags({"redis", "redis data reader"})
@CapabilityDescription("redis data reader")
public class RedisDataReaderProcessor extends AbstractProcessor {
    private final Set<Relationship> relationships;
    protected List<PropertyDescriptor> propDescriptors;
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .description("success")
            .name("success")
            .build();
    public static final Relationship REL_FAILURE = new Relationship.Builder().description("failure").name("failures").build();


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
    public static final PropertyDescriptor REDIS_DATA_TYPE = new PropertyDescriptor.Builder()
            .name("REDIS_DATA_TYPEs")
            .displayName("redis data type")
            .description("redis data type")
            .allowableValues("String","Set","List","Hash","ZSet")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();
    public static final PropertyDescriptor REDIS_DATA_NAME = new PropertyDescriptor.Builder()
            .name("REDIS_DATA_NAME")
            .displayName("redis data name")
            .description("redis data name")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();

    public RedisDataReaderProcessor() {
        final Set<Relationship> relationshipSet = new HashSet<>();
        relationshipSet.add(REL_SUCCESS);
        relationshipSet.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(relationshipSet);
        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(REDIS_IP);
        pds.add(REDIS_PORT);
        pds.add(REDIS_PWD);
        pds.add(REDIS_DATA_TYPE);
        pds.add(REDIS_DATA_NAME);
        propDescriptors = Collections.unmodifiableList(pds);
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propDescriptors;
    }
    public Set<Relationship> getRelationships() {
        return this.relationships;
    }

    @Override
    public void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {
        String redisIp = context.getProperty(REDIS_IP).getValue();
        String redisPort = context.getProperty(REDIS_PORT).getValue();
        String redisDataType = context.getProperty(REDIS_DATA_TYPE).getValue();
        String redisDataName = context.getProperty(REDIS_DATA_NAME).getValue();
        String redisPassword = context.getProperty(REDIS_PWD).getValue();
        Jedis jedis = JedisUtil.getJedis(redisIp, Integer.parseInt(redisPort), redisPassword);
        RedisReadEntity result = new RedisReadEntity();
        if ("String".equals(redisDataType)){
            String value = jedis.get(redisDataName);
            if (value!=null && !value.isEmpty()){
                result.setKey(redisDataName);
                result.setValue(value);
            }

        } else if ("Set".equals(redisDataType)){
            Set<String> set = jedis.smembers(redisDataName);
            if (set!=null && !set.isEmpty()){
                result.setKey(redisDataName);
                result.setValue(set);
            }

        } else if ("List".equals(redisDataType)){
            List<String> list = jedis.brpop(0, redisDataName);
            if (list != null && list.size() == 2) {
                String key = list.get(0);
                String value = list.get(1);
                result.setKey(key);
                result.setValue(value);
            }
        } else if ("Hash".equals(redisDataType)){
            Map<String, String> stringStringMap = jedis.hgetAll(redisDataName);
            if (stringStringMap!=null && !stringStringMap.isEmpty()){
                result.setKey(redisDataName);
                result.setValue(JSONObject.toJSONString(stringStringMap));
            }

        } else if ("ZSet".equals(redisDataType)){
           // to do
        }
        FlowFile flowFile = session.create();
        if (result.getValue()!=null){
            flowFile = session.write(flowFile, new StreamCallback() {
                @Override
                public void process(InputStream in, OutputStream out) throws IOException {
                    out.write(JSONObject.toJSONString(result).getBytes(StandardCharsets.UTF_8));
                }
            });

            Map<String, String> generatedAttributes = new HashMap<String, String>();
            generatedAttributes.put(CoreAttributes.MIME_TYPE.key(), "application/json");
            flowFile = session.putAllAttributes(flowFile, generatedAttributes);
            session.transfer(flowFile,REL_SUCCESS);
        } else {
            session.transfer(flowFile,REL_FAILURE);
        }
    }
}
