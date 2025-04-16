package com.ztgx.nifi.processor;

import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.util.StandardValidators;

import java.util.*;

@SideEffectFree
@Tags({"uniswapv3", "meta"})
@CapabilityDescription("uniswapv3 token pair factory pool 元数据同步抓取")
public class UniswapV3TokenMetaProcessor extends AbstractProcessor {
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

    public UniswapV3TokenMetaProcessor() {
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

    }
}
