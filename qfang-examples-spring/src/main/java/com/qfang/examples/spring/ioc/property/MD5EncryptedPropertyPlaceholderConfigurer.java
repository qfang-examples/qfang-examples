package com.qfang.examples.spring.ioc.property;

import org.springframework.beans.factory.config.PropertyPlaceholderConfigurer;

/**
 * Created by walle on 2017/4/16.
 */
public class MD5EncryptedPropertyPlaceholderConfigurer extends PropertyPlaceholderConfigurer {

    private static final String PASSWORD = "password";

    @Override
    protected String convertProperty(String propertyName, String propertyValue) {
        if(PASSWORD.equals(propertyName)) {
            // 对配置文件中 password 属性进行加密的功能，配置文件中配置的是明文，但是设置到的 bean 属性中时是密文
            return MD5Utils.encrypted(propertyValue);
        }
        return super.convertProperty(propertyName, propertyValue);
    }

}
