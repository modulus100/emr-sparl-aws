package org.example.kafkatools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;

public final class OffsetLagCommitter {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Map<String, Long>>> OFFSETS_JSON_TYPE =
            new TypeReference<>() {
            };

    private OffsetLagCommitter() {
    }

    public static String commitOffsetsAndUpdateLag(
            String bootstrapServers,
            String consumerGroup,
            String offsetsJson) {

        Map<TopicPartition, Long> nextOffsets = parseOffsets(offsetsJson);
        if (nextOffsets.isEmpty()) {
            return "{\"consumerGroup\":\"" + consumerGroup + "\",\"totalLag\":0,\"partitions\":{}}";
        }

        Properties props = new Properties();
        props.put("bootstrap.servers", bootstrapServers);
        props.put("request.timeout.ms", "30000");
        // Example for Amazon MSK IAM auth with an attached IAM role (IRSA / EC2 role).
        // Uncomment when using IAM-enabled broker endpoints, usually on port 9098:
        //
        // props.put("security.protocol", "SASL_SSL");
        // props.put("sasl.mechanism", "AWS_MSK_IAM");
        // props.put(
        //         "sasl.jaas.config",
        //         "software.amazon.msk.auth.iam.IAMLoginModule required;"
        // );
        // props.put(
        //         "sasl.client.callback.handler.class",
        //         "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
        // );
        //
        // Example for explicit STS assume-role from the committer itself:
        //
        // props.put("security.protocol", "SASL_SSL");
        // props.put("sasl.mechanism", "AWS_MSK_IAM");
        // props.put(
        //         "sasl.jaas.config",
        //         "software.amazon.msk.auth.iam.IAMLoginModule required "
        //                 + "awsRoleArn=\"arn:aws:iam::123456789012:role/YourMskAccessRole\" "
        //                 + "awsRoleSessionName=\"offset-committer\" "
        //                 + "awsStsRegion=\"us-east-1\";"
        // );
        // props.put(
        //         "sasl.client.callback.handler.class",
        //         "software.amazon.msk.auth.iam.IAMClientCallbackHandler"
        // );
        //
        // To use the examples above, add dependency:
        // software.amazon.msk:aws-msk-iam-auth
        // and connect to the IAM-enabled MSK bootstrap brokers.

        try (AdminClient admin = AdminClient.create(props)) {
            Map<TopicPartition, OffsetAndMetadata> commitPayload = new HashMap<>();
            Map<TopicPartition, OffsetSpec> latestRequest = new HashMap<>();
            for (Map.Entry<TopicPartition, Long> entry : nextOffsets.entrySet()) {
                commitPayload.put(entry.getKey(), new OffsetAndMetadata(entry.getValue()));
                latestRequest.put(entry.getKey(), OffsetSpec.latest());
            }

            AlterConsumerGroupOffsetsResult commitResult =
                    admin.alterConsumerGroupOffsets(consumerGroup, commitPayload);
            commitResult.all().get(30, TimeUnit.SECONDS);

            ListOffsetsResult offsetsResult = admin.listOffsets(latestRequest);
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> latestOffsets =
                    offsetsResult.all().get(30, TimeUnit.SECONDS);

            long totalLag = 0L;
            Map<String, Map<String, Long>> lagByTopic = new LinkedHashMap<>();

            for (Map.Entry<TopicPartition, Long> entry : nextOffsets.entrySet()) {
                TopicPartition tp = entry.getKey();
                long committedOffset = entry.getValue();
                long latestOffset = latestOffsets.get(tp).offset();
                long lag = Math.max(0L, latestOffset - committedOffset);
                totalLag += lag;

                lagByTopic.computeIfAbsent(tp.topic(), ignored -> new LinkedHashMap<>())
                        .put(Integer.toString(tp.partition()), lag);
            }

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("consumerGroup", consumerGroup);
            payload.put("totalLag", totalLag);
            payload.put("partitions", lagByTopic);
            return OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to commit offsets and update lag", e);
        }
    }

    private static Map<TopicPartition, Long> parseOffsets(String offsetsJson) {
        if (offsetsJson == null || offsetsJson.isBlank()) {
            return Map.of();
        }

        try {
            Map<String, Map<String, Long>> decoded = OBJECT_MAPPER.readValue(offsetsJson, OFFSETS_JSON_TYPE);
            Map<TopicPartition, Long> result = new LinkedHashMap<>();
            for (Map.Entry<String, Map<String, Long>> topicEntry : decoded.entrySet()) {
                String topic = topicEntry.getKey();
                for (Map.Entry<String, Long> partitionEntry : topicEntry.getValue().entrySet()) {
                    int partition = Integer.parseInt(partitionEntry.getKey());
                    result.put(new TopicPartition(topic, partition), partitionEntry.getValue());
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid offsets JSON. Expected {topic:{partition:nextOffset}}", e);
        }
    }
}
