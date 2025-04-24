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
@Tags({"GoPlusResult", "GoPlus http reslut"})
@CapabilityDescription("GoPlus http reslut")
public class GoPlusHttpResultProcessor extends AbstractProcessor {
    private final Set<Relationship> relationships;
    protected List<PropertyDescriptor> propDescriptors;
    public static final Relationship REL_BASIC_INFO = new Relationship.Builder()
            .description("tioken basic info")
            .name("token_basic_info")
            .build();
    public static final Relationship REL_DEX_INFO = new Relationship.Builder().description("dex info").name("dex_info").build();
    public static final Relationship REL_CEX_INFO = new Relationship.Builder().description("cex info").name("cex_info").build();
    public static final Relationship REL_HOLDERS_INFO = new Relationship.Builder().description("token holder info").name("token_holders_info").build();
    public static final Relationship REL_LP_HOLDERS_INFO = new Relationship.Builder().description("token lp holder info").name("token_lp_holders_info").build();
    public static final Relationship REL_FAILURE = new Relationship.Builder().description("failure").name("failures").build();
    public GoPlusHttpResultProcessor() {
        final Set<Relationship> relationshipSet = new HashSet<>();
        relationshipSet.add(REL_BASIC_INFO);
        relationshipSet.add(REL_DEX_INFO);
        relationshipSet.add(REL_HOLDERS_INFO);
        relationshipSet.add(REL_CEX_INFO);
        relationshipSet.add(REL_LP_HOLDERS_INFO);
        relationshipSet.add(REL_FAILURE);
        relationships = Collections.unmodifiableSet(relationshipSet);
    }

    @Override
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
        JSONObject jObject = JSON.parseObject(data);
        if (jObject.getString("code").equals("1")){
            JSONObject tokenSafeInfo = new JSONObject();
            JSONObject jsonObject =jObject.getJSONObject("result");
            for (String key : jsonObject.keySet()){
                JSONObject result = jsonObject.getJSONObject(key);
                tokenSafeInfo.put("token_address", key);
                tokenSafeInfo.put("anti_whale_modifiable",result.getString("anti_whale_modifiable"));
                tokenSafeInfo.put("buy_tax",result.getString("buy_tax"));
                tokenSafeInfo.put("can_take_back_ownership",result.getString("can_take_back_ownership"));
                tokenSafeInfo.put("cannot_buy",result.getString("cannot_buy"));
                tokenSafeInfo.put("cannot_sell_all",result.getString("cannot_sell_all"));
                tokenSafeInfo.put("creator_address",result.getString("creator_address"));
                tokenSafeInfo.put("creator_balance",result.getString("creator_balance"));
                tokenSafeInfo.put("creator_percent",result.getString("creator_percent"));
                tokenSafeInfo.put("external_call",result.getString("external_call"));
                tokenSafeInfo.put("hidden_owner",result.getString("hidden_owner"));
                tokenSafeInfo.put("holder_count",result.getString("holder_count"));
                tokenSafeInfo.put("honeypot_with_same_creator",result.getString("honeypot_with_same_creator"));
                tokenSafeInfo.put("is_anti_whale",result.getString("is_anti_whale"));
                tokenSafeInfo.put("is_blacklisted",result.getString("is_blacklisted"));
                tokenSafeInfo.put("is_honeypot",result.getString("is_honeypot"));
                tokenSafeInfo.put("is_in_dex",result.getString("is_in_dex"));
                tokenSafeInfo.put("is_mintable",result.getString("is_mintable"));
                tokenSafeInfo.put("is_open_source",result.getString("is_open_source"));
                tokenSafeInfo.put("is_proxy",result.getString("is_proxy"));
                tokenSafeInfo.put("is_whitelisted",result.getString("is_whitelisted"));
                tokenSafeInfo.put("lp_holder_count",result.getString("lp_holder_count"));
                tokenSafeInfo.put("lp_total_supply",result.getString("lp_total_supply"));
                tokenSafeInfo.put("owner_address",result.getString("owner_address"));
                tokenSafeInfo.put("owner_balance",result.getString("owner_balance"));
                tokenSafeInfo.put("owner_change_balance",result.getString("owner_change_balance"));
                tokenSafeInfo.put("owner_percent",result.getString("owner_percent"));
                tokenSafeInfo.put("personal_slippage_modifiable",result.getString("personal_slippage_modifiable"));
                tokenSafeInfo.put("selfdestruct",result.getString("selfdestruct"));
                tokenSafeInfo.put("sell_tax",result.getString("sell_tax"));
                tokenSafeInfo.put("slippage_modifiable",result.getString("slippage_modifiable"));
                tokenSafeInfo.put("token_name",result.getString("token_name"));
                tokenSafeInfo.put("token_symbol",result.getString("token_symbol"));
                tokenSafeInfo.put("total_supply",result.getString("total_supply"));
                tokenSafeInfo.put("trading_cooldown",result.getString("trading_cooldown"));
                tokenSafeInfo.put("transfer_pausable",result.getString("transfer_pausable"));
                tokenSafeInfo.put("transfer_tax",result.getString("transfer_tax"));
                JSONObject isInCex = result.getJSONObject("is_in_cex");
                if (result.getJSONObject("is_in_cex")!=null){
                    JSONArray cexlist = isInCex.getJSONArray("cex_list");
                    if (cexlist!=null && !cexlist.isEmpty()){
                        tokenSafeInfo.put("is_in_cex","1");
                    } else {
                        tokenSafeInfo.put("is_in_cex","0");
                    }

                } else {
                    tokenSafeInfo.put("is_in_cex","0");
                }

                transfer (session,REL_BASIC_INFO,tokenSafeInfo,flowFile);

                JSONArray holders = result.getJSONArray("holders");
                if (holders!=null){
                    for (int i = 0; i < holders.size(); i++){
                        JSONObject holder = holders.getJSONObject(i);
                        holder.put("token_address",key);
                        String address = holder.getString("address");
                        holder.put("id",key+"_"+address);
                        flowFile = session.create();
                        transfer (session,REL_HOLDERS_INFO,holder,flowFile);
                    }
                }


                JSONArray lpHolders = result.getJSONArray("lp_holders");
                if (lpHolders!=null){
                    for (int i = 0; i < lpHolders.size(); i++){
                        JSONObject lpHolder = lpHolders.getJSONObject(i);
                        lpHolder.put("token_address",key);
                        String address = lpHolder.getString("address");
                        lpHolder.put("id",key+"_"+address);
                        flowFile = session.create();
                        transfer (session,REL_LP_HOLDERS_INFO,lpHolder,flowFile);
                    }
                }

                if (isInCex != null){
                    JSONArray cexlist = isInCex.getJSONArray("cex_list");
                    if (cexlist!=null && !cexlist.isEmpty()){
                        for (int i = 0; i < cexlist.size(); i++){
                            String cex = cexlist.getString(i);
                            JSONObject obj = new JSONObject();
                            obj.put("token_address",key);
                            obj.put("id",key+"_"+cex);
                            obj.put("cex",cex);
                            flowFile = session.create();
                            transfer(session,REL_CEX_INFO,obj,flowFile);
                        }
                    }
                }
                JSONArray dexes = result.getJSONArray("dex");
                if (dexes!=null && !dexes.isEmpty()){
                    for (int i = 0; i < dexes.size(); i++){
                        JSONObject dex = dexes.getJSONObject(i);
                        String pair = dex.getString("pair");
                        String liquidity_type = dex.getString("liquidity_type");
                        dex.put("token_address",key);
                        dex.put("id",key+"_"+pair+"_"+liquidity_type);
                        flowFile = session.create();
                        transfer(session,REL_DEX_INFO,dex,flowFile);
                    }
                }
            }
        } else {
            session.transfer(flowFile,REL_FAILURE);
        }
    }

    private void transfer(ProcessSession session, Relationship relBasicInfo, JSONObject info,FlowFile flowFile) {
        Map<String, String> attributes = new HashMap<String, String>();
        attributes.put(CoreAttributes.MIME_TYPE.key(), "application/json");
        // FlowFile flowFile = session.create();
        flowFile = session.write(flowFile, new StreamCallback() {
            @Override
            public void process(InputStream in, OutputStream out) throws IOException {
                out.write(JSONObject.toJSONString(info, SerializerFeature.WriteMapNullValue)
                        .getBytes(StandardCharsets.UTF_8));
            }
        });
        flowFile = session.putAllAttributes(flowFile, attributes);
        session.transfer(flowFile, relBasicInfo);
    }
}
