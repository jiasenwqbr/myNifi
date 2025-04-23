package com.ztgx.nifi.util;

import com.alibaba.fastjson.JSONObject;
import com.ztgx.nifi.processor.entity.CTResponse;
import org.springframework.http.MediaType;

public class RuleUtil {

    public static  CTResponse postRule(String params, String cleanURI)
    {
        String restUrl = cleanURI;
        JSONObject jsonObj = JSONObject.parseObject(params);
        MediaType type = MediaType.parseMediaType("application/json; charset=UTF-8");
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(type);
        headers.add("Accept", MediaType.APPLICATION_JSON.toString());
        org.springframework.http.HttpEntity<String> formEntity = new org.springframework.http.HttpEntity<String>(
                jsonObj.toString(), headers);
        CTResponse resp = new CTResponse();
        try{
            resp = RestClient.getClient().postForObject(restUrl, formEntity, CTResponse.class);

        }catch(Exception e){
            e.printStackTrace();
        }

        return resp;
    }

    public static  CTResponse getRule( String cleanURI)
    {
        String restUrl = cleanURI;
        MediaType type = MediaType.parseMediaType("application/json; charset=UTF-8");
        org.springframework.http.HttpHeaders headers = new org.springframework.http.HttpHeaders();
        headers.setContentType(type);
        headers.add("Accept", MediaType.APPLICATION_JSON.toString());
//        org.springframework.http.HttpEntity<String> formEntity = new org.springframework.http.HttpEntity<String>(
//                jsonObj.toString(), headers);
        CTResponse resp = new CTResponse();
        try{
            resp = RestClient.getClient().getForObject(restUrl, CTResponse.class);

        }catch(Exception e){
            e.printStackTrace();
        }

        return resp;
    }




    public static void main(String[] args) {
        ///String params = "{\"parameter1\":\"913501006830690573\"}";
        String url = "https://api.gopluslabs.io/api/v1/token_security/1?contract_addresses=0xee2a03aa6dacf51c18679c516ad5283d8e7c2637";
//        CTResponse r = getRule(url);
//        System.out.println(r.getResult());

        GraphQLUtil.commonGet(url,"application/json; charset=UTF-8");
    }

}
