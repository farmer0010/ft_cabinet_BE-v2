package com.gyeongsan.cabinet.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;

@Log4j2
public class InitMarkerSkippingListener
        implements StreamListener<String, MapRecord<String, String, String>> {

    private final StreamListener<String, MapRecord<String, String, String>> delegate;
    private final StringRedisTemplate template;
    private final String group;

    public InitMarkerSkippingListener(
            StreamListener<String, MapRecord<String, String, String>> delegate,
            StringRedisTemplate template,
            String group) {
        this.delegate = delegate;
        this.template = template;
        this.group = group;
    }

    @Override
    public void onMessage(MapRecord<String, String, String> message) {
        if (message.getValue().containsKey("init")) {
            template.opsForStream().acknowledge(message.getStream(), group, message.getId());
            template.opsForStream().delete(message.getStream(), message.getId());
            return;
        }
        delegate.onMessage(message);
    }
}
