package com.ztgx.nifi.util;

import redis.clients.jedis.Jedis;

public class JedisUtil {
    public static Jedis getJedis(String host,int port,String password){
        Jedis jedis = new Jedis(host, port);
        jedis.auth(password);
        return jedis;
    }
}
