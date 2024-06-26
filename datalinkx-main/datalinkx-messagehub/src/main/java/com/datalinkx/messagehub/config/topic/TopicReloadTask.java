package com.datalinkx.messagehub.config.topic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TimerTask;
import java.util.stream.Collectors;

import javax.annotation.Resource;
import javax.persistence.EntityManager;
import javax.persistence.Query;

import com.datalinkx.common.constants.MessageHubConstants;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.SQLQuery;
import org.hibernate.transform.Transformers;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.util.ObjectUtils;



@Slf4j
@Service
@ConditionalOnProperty(prefix = "topic",name = "daemon",havingValue = "true")
public class TopicReloadTask extends TimerTask {

    @Resource
    StringRedisTemplate stringRedisTemplate;

    @Resource
    EntityManager entityManager;

    public final String TOPIC_SQL = " select topic, info_type from MESSAGEHUB_TOPIC where is_del = 0 ";
    public final String LUA_SCRIPT =
                "redis.call('del', '" + MessageHubConstants.WHITE_TOPIC +  "')" +
                "local topics = KEYS " +
                "redis.call('SADD', '" + MessageHubConstants.WHITE_TOPIC + "', unpack(topics)) ";


    //只有在推送流转进度中使用了消息通道能力，
    // datalinkx-job作为生产者，检测到流转任务的进度数据后通过调用produce api把数据发送到reids中，
    // 而datalinkx-sse作为消费者，通过Spring生命周期，注册一个常驻代理线程，实时监控reids中的数据，
    // 监听到数据后回调到标记了@MessaheHub注解中的方法。
    @Override
    public void run() {
        try {
            List<Map<String, Object>> topics = this.getQueryResult(TOPIC_SQL);
            if (!ObjectUtils.isEmpty(topics)) {
                // 转化的形式REDIS_STREAM:JOB_PROCESS
                List<String> mappingTopics = topics.stream().map(
                        v -> MessageHubConstants.getInnerTopicName(
                                (String) v.get("info_type"),
                                (String) v.get("topic")
                        )
                ).collect(Collectors.toList());

                DefaultRedisScript<Long> redisScript = new DefaultRedisScript<>(LUA_SCRIPT, Long.class);
                this.stringRedisTemplate.execute(redisScript, mappingTopics);
                log.info("messagehub topic reload finish：消息队列topic完成刷新【topic是某个业务的key】");
            } else {
                log.warn("messagehub topic is empty");
            }
        } catch (Throwable t) {

            log.error("messagehub topic reload error", t);
        }
    }

    private <T> List<T> getQueryResult(String sql, Class<T> clazz) {
        Query dataQuery = this.entityManager.createNativeQuery(sql, clazz);
        List<T> result = new ArrayList<>();
        List<Object> list = dataQuery.getResultList();
        for (Object o : list) {
            result.add((T) o);
        }

        return result;
    }

    private <T> List<T> getQueryResult(String sql) {
        Query dataQuery = this.entityManager.createNativeQuery(sql);
        dataQuery.unwrap(SQLQuery.class).setResultTransformer(Transformers.ALIAS_TO_ENTITY_MAP);
        return dataQuery.getResultList();
    }
}
