package org.apache.nifi.blockchain.uniswapv2;

import org.apache.nifi.annotation.behavior.EventDriven;
import org.apache.nifi.annotation.behavior.SideEffectFree;
import org.apache.nifi.annotation.documentation.CapabilityDescription;
import org.apache.nifi.annotation.documentation.Tags;
import org.apache.nifi.components.PropertyDescriptor;
import org.apache.nifi.components.PropertyValue;
import org.apache.nifi.expression.ExpressionLanguageScope;
import org.apache.nifi.flowfile.FlowFile;
import org.apache.nifi.processor.AbstractProcessor;
import org.apache.nifi.processor.ProcessContext;
import org.apache.nifi.processor.ProcessSession;
import org.apache.nifi.processor.Relationship;
import org.apache.nifi.processor.exception.ProcessException;
import org.apache.nifi.processor.io.OutputStreamCallback;
import org.apache.nifi.processor.util.StandardValidators;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Tags({"uni swap", "v2", "blockchain"})
@CapabilityDescription("uni swapV2")
@EventDriven
public class UniswapV2Processor extends AbstractProcessor {

    // json template
    public static final PropertyDescriptor jsonTempleate = new PropertyDescriptor.Builder()
            .name("jsonTempleate")
            .description("a json template for processing")
            .required(true)
            .defaultValue("")
            .expressionLanguageSupported(ExpressionLanguageScope.VARIABLE_REGISTRY)
            .addValidator(StandardValidators.NON_EMPTY_EL_VALIDATOR)
            .build();

    private  final List<PropertyDescriptor> propertyDescriptors ;
    private  final Set<Relationship> relationships ;

    public  final Relationship REL_SUCCESS = new Relationship.Builder()
            .name("success")
            .description("All FlowFiles that are received from the AMQP queue are routed to this relationship")
            .build();

    public UniswapV2Processor() {
        final Set<Relationship> relationshipSet = new HashSet<>();
        relationshipSet.add(REL_SUCCESS);
        relationships = Collections.unmodifiableSet(relationshipSet);
        final List<PropertyDescriptor> pds = new ArrayList<>();
        pds.add(jsonTempleate);
        propertyDescriptors = Collections.unmodifiableList(pds);
    }

    @Override
    public Set<Relationship> getRelationships() {
        return relationships;
    }

    @Override
    protected List<PropertyDescriptor> getSupportedPropertyDescriptors() {
        return propertyDescriptors;
    }


    @Override
    public synchronized void onTrigger(ProcessContext context, ProcessSession session) throws ProcessException {

        PropertyValue property = context.getProperty(jsonTempleate);
        String template = property.getValue();

        // do some process
        FlowFile flowFile = session.get();
        session.write(flowFile, new OutputStreamCallback() {
            @Override
            public void process(OutputStream out) throws IOException {
                out.write(template.getBytes(StandardCharsets.UTF_8));
            }
        });
        session.transfer(flowFile,REL_SUCCESS);
    }
}
