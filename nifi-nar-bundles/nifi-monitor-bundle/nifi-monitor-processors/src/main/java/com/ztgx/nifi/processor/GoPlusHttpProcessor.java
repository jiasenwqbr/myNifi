package com.ztgx.nifi.processor;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.ztgx.nifi.processor.entity.CTResponse;
import com.ztgx.nifi.util.GraphQLUtil;
import com.ztgx.nifi.util.RestClient;
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
import org.apache.nifi.processor.util.StandardValidators;
import org.apache.nifi.stream.io.StreamUtils;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

@SideEffectFree
@Tags({"GoPlus", "GoPlus http get"})
@CapabilityDescription("GoPlus http get")
public class GoPlusHttpProcessor extends AbstractProcessor {


    private final Set<Relationship> relationships;
    protected List<PropertyDescriptor> propDescriptors;
    public static final Relationship REL_SUCCESS = new Relationship.Builder()
            .description("success")
            .name("success")
            .build();
    public static final Relationship REL_FAILURE = new Relationship.Builder().description("failure").name("failures").build();
    public static final PropertyDescriptor GOPLUS_URL = new PropertyDescriptor.Builder()
            .name("GOPLUS_URL")
            .displayName("GOPLUS URL")
            .description("GOPLUS URL")
            .addValidator(StandardValidators.NON_EMPTY_VALIDATOR)
            .required(true)
            .build();
    public GoPlusHttpProcessor() {
        final Set<Relationship> relationshipSet = new HashSet<>();
        relationshipSet.add(REL_SUCCESS);
        relationshipSet.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(relationshipSet);
        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(GOPLUS_URL);

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

        MediaType type = MediaType.parseMediaType("application/json; charset=UTF-8");
        final Charset charset = Charset.forName(StandardCharsets.UTF_8.name());
        String data = new String(content, charset);
        JSONObject jsonObject = JSON.parseObject(data);
        String value = jsonObject.getString("value");
        String url = context.getProperty(GOPLUS_URL).getValue()+value;
        String resp = GraphQLUtil.commonGet(url,"application/json; charset=UTF-8");

       // flowFile = session.create();
        if (resp != null) {
                flowFile = session.write(flowFile, new StreamCallback() {
                    @Override
                    public void process(InputStream in, OutputStream out) throws IOException {
                        out.write(resp.getBytes(StandardCharsets.UTF_8));
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
