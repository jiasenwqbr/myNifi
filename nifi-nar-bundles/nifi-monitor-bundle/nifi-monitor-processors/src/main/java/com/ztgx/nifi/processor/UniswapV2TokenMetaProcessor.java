package com.ztgx.nifi.processor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import com.ztgx.nifi.util.GraphQLUtil;
import com.ztgx.nifi.util.JedisUtil;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
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
@Tags({"uniswapv2", "meta"})
@CapabilityDescription("uniswapv2 token pair元数据同步抓取")
public class UniswapV2TokenMetaProcessor extends AbstractProcessor {
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
    public static final PropertyDescriptor GRAP_URL = new PropertyDescriptor.Builder()
            .name("GRAP_URL")
            .displayName("The graph url")
            .description("The graph url")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();
    public static final PropertyDescriptor GRAP_API_KEY = new PropertyDescriptor.Builder()
            .name("GRAP_API_KEY")
            .displayName("The graph api key")
            .description("The graph api key")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();
    public static final PropertyDescriptor SKIP_NUMBER = new PropertyDescriptor.Builder()
            .name("SKIP_NUMBER")
            .displayName("skip number")
            .description("skip number")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();
    public static final PropertyDescriptor STEP_NUMBER = new PropertyDescriptor.Builder()
            .name("STEP_NUMBER")
            .displayName("step number")
            .description("step number")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();




    public UniswapV2TokenMetaProcessor() {
        final Set<Relationship> relationshipSet = new HashSet<>();
        relationshipSet.add(REL_SUCCESS);
        relationshipSet.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(relationshipSet);
        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(JSON_TEMPLATE);
        pds.add(REDIS_IP);
        pds.add(REDIS_PORT);
        pds.add(REDIS_PWD);
        pds.add(SKIP_NUMBER);
        pds.add(GRAP_URL);
        pds.add(GRAP_API_KEY);
        pds.add(STEP_NUMBER);

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
        String skipNumber = context.getProperty(SKIP_NUMBER).getValue();
        String graphUrl = context.getProperty(GRAP_URL).getValue();
        String stepNumber = context.getProperty(STEP_NUMBER).getValue();
        String apiKey = context.getProperty(GRAP_API_KEY).getValue();
        Jedis jedis = JedisUtil.getJedis(redisIp, Integer.parseInt(redisPort), redisPwd);
        String currentSkipNumber = jedis.get("uniSwapV2CurrentSkipNumber_tokens_pairs");
        if (currentSkipNumber == null) {
            currentSkipNumber = stepNumber;
            jedis.set("uniSwapV2CurrentSkipNumber_tokens_pairs",currentSkipNumber);
        } else {
            currentSkipNumber = String.valueOf(Integer.parseInt(currentSkipNumber) + Integer.parseInt(stepNumber)) ;
        }
        FlowFile flowFile = session.create();
        String newBody = template.replace("<<<currentSkipNumber>>>",currentSkipNumber).replace("<<<stepNumber>>>",stepNumber);
        // String post = GraphQLUtil.post(graphUrl, "application/json", newBody);
        String post = GraphQLUtil.postWithApiKey(graphUrl, "application/json", newBody,apiKey);
        JSONObject jsonObject = JSON.parseObject(newBody);
        boolean isFinish = isFinished(post);
        if (!isFinish){
            flowFile = session.write(flowFile, new StreamCallback() {
                @Override
                public void process(InputStream in, OutputStream out) throws IOException {
                    assert post != null;
                    out.write(post.getBytes(StandardCharsets.UTF_8));
                }
            });
            Map<String, String> generatedAttributes = new HashMap<String, String>();
            generatedAttributes.put(CoreAttributes.MIME_TYPE.key(), "application/json");
            flowFile = session.putAllAttributes(flowFile, generatedAttributes);
            jedis.set("uniSwapV2CurrentSkipNumber_tokens_pairs",currentSkipNumber);
            session.transfer(flowFile,REL_SUCCESS);
        } else {
            session.transfer(flowFile,REL_FAILURE);
        }
    }

    private boolean isFinished(String post) {
        boolean isBlocked = false;
        JSONObject jsonObject = JSON.parseObject(post);
        if (jsonObject!=null && !jsonObject.isEmpty()){
            if (jsonObject.containsKey("data")){
                JSONObject data = jsonObject.getJSONObject("data");
                if (data!=null){
                    JSONArray pairs = data.getJSONArray("pairs");
                    JSONArray tokens = data.getJSONArray("tokens");
                    if (pairs==null &&  tokens==null){
                        isBlocked = true;
                    } else {
                        if (pairs.isEmpty() && tokens.isEmpty()){
                            isBlocked = true;
                        }
                    }
                } else {
                    isBlocked = true;
                }
            }else {
                isBlocked = true;
            }
        }else {
            isBlocked = true;
        }

        return isBlocked;
    }


}
