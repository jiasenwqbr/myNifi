package com.ztgx.nifi.processor.entity;

import java.io.Serializable;

public class RedisReadEntity implements Serializable {
    String key ;

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    Object value ;
}
