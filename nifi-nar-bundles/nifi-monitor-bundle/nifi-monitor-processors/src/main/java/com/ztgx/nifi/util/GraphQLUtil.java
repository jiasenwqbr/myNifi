package com.ztgx.nifi.util;

import org.apache.http.HttpEntity;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;

public class GraphQLUtil {
    public static  String  post(String url,String contentType,String query){
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            // 1. 创建 HttpPost 请求
            HttpPost post = new HttpPost(url);

            // 2. 设置请求头
            post.setHeader("Content-Type", contentType);

            // 3. 创建请求体
            // String query = "{ \"query\": \"{ users { id name } }\" }";
            StringEntity entity = new StringEntity(query);
            post.setEntity(entity);

            // 4. 执行请求并获取响应
            org.apache.http.HttpResponse response = client.execute(post);
            HttpEntity responseEntity = response.getEntity();

            // 5. 打印响应
            String responseString = EntityUtils.toString(responseEntity);
            System.out.println("url is :"+url +"\n query is :"+query+"\n response is :"+responseString);
            return responseString;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    public static  String  postWithApiKey(String url,String contentType,String query,String apiKey){
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            // 1. 创建 HttpPost 请求
            HttpPost post = new HttpPost(url);

            // 2. 设置请求头
            post.setHeader("Content-Type", contentType);
            post.setHeader("Authorization", "Bearer "+apiKey);

            // 3. 创建请求体
            // String query = "{ \"query\": \"{ users { id name } }\" }";
            StringEntity entity = new StringEntity(query);
            post.setEntity(entity);

            // 4. 执行请求并获取响应
            org.apache.http.HttpResponse response = client.execute(post);
            HttpEntity responseEntity = response.getEntity();

            // 5. 打印响应
            String responseString = EntityUtils.toString(responseEntity);
            System.out.println("url is :"+url +"\n query is :"+query+"\n response is :"+responseString);
            return responseString;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }
}
