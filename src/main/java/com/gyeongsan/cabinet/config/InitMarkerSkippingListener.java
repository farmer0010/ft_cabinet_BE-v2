package com.gyeongsan.cabinet.config;

import lombok.extern.log4j.Log4j2;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamListener;

/**
 * 스트림을 처음 만들 때 넣는 {@code init} 표식 레코드(스트림을 존재하게 하려는 용도)를 소비자에게 넘기지 않고 ACK·삭제한다.
 *
 * <p>소비자 그룹을 처음부터({@code 0}) 다시 만들면 이 표식도 배달되는데, 업무 필드가 없어 소비자가 NPE 로 처리하거나 {@code null} 로 슬랙 DM 을
 * 보내려 할 수 있다. 그 전에 걸러낸다.
 */
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
