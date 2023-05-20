/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: MIT-0
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this
 * software and associated documentation files (the "Software"), to deal in the Software
 * without restriction, including without limitation the rights to use, copy, modify,
 * merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
 * INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 * HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION
 * OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package sample.msk.kafka.quota.blog;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.auth.credentials.SystemPropertyCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchClient;
import software.amazon.awssdk.services.cloudwatch.model.Dimension;
import software.amazon.awssdk.services.cloudwatch.model.MetricDatum;
import software.amazon.awssdk.services.cloudwatch.model.PutMetricDataRequest;
import software.amazon.awssdk.services.cloudwatch.model.StandardUnit;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.sts.model.AssumeRoleRequest;
import software.amazon.awssdk.services.sts.model.AssumeRoleResponse;
import software.amazon.awssdk.services.sts.model.Credentials;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

public class KafkaConsumer {

    private final static Logger logger = LoggerFactory.getLogger(KafkaConsumer.class.getName());

    @Parameter(names = {"--help", "-h"}, help = true)
    private boolean help = false;
    @Parameter(names={"--bootstrap-servers"},description="Amazon MSK cluster bootstrap servers IAM endpoint")
    private String bootstrapServers;
    @Parameter(names={"--assume-role-arn"},description="IAM role ARN in a Shared Services Account that the consumer will assume")
    private String assumeRoleARN;
    @Parameter(names={"--region"},description="AWS Region where you want to point AWS STS client to e.g. us-east-1 Default is us-east-1")
    private String regionName = "ap-southeast-2";
    @Parameter(names={"--topic-name"},description="Kafka topic name on MSK cluster. Default is Topic-B")
    private String topic = "Topic-B";
    @Parameter(names={"--consumer-group"},description="Kafka Consumer's consumer group")
    private String consumerGroup;
    @Parameter(names={"--role-session-name"},description="IAM role session name for the STS assumerole call")
    private String roleSessionName;
    @Parameter(names={"--client-id"},description="Consumer application client id")
    private String clientId;
    @Parameter(names={"--print-consumer-quota-metrics"},description="Flag Y or N to decide whether to print consumer quota metrics")
    private String printConsumerQuotaMetrics = "N";
    @Parameter(names={"--cw-dimension-name"},description="Specify CloudWatch dimension name")
    private String dimensionName = "ConsumerApp";
    @Parameter(names={"--cw-dimension-value"},description="Specify CloudWatch dimension value")
    private String dimensionValue;
    @Parameter(names={"--cw-namespace"},description="Specify CloudWatch namespace")
    private String namespace;

    private StsClient stsClient = null;
    private CloudWatchClient cwClient = null;

    private MetricName consumerFetchThrottleTimeAvgMetricName = null;
    private MetricName consumerFetchThrottleTimeMaxMetricName = null;

    public static void main(String[] args) {
        KafkaConsumer consumer = new KafkaConsumer();
        JCommander jc = JCommander.newBuilder()
                .addObject(consumer)
                .build();
        jc.parse(args);
        if (consumer.help){
            jc.usage();
            return;
        }
        consumer.startConsumer();
    }
    public KafkaConsumer(){
        Region region = Region.of(regionName);
        if(!Region.regions().contains(region))
            throw new RuntimeException("Region : " + regionName + " is invalid.");
        stsClient = StsClient.builder().region(region).build();
    }
    /**
     * This starts the Kafka consumer. In order to read events from the Kafka topic, 
     * it first assumes an IAM role specified as a command-line argument.
     * @return Nothing
     */
    public void startConsumer() {
        logger.info("starting consumer...");
        //Assume TopicB Read IAM role
        assumeMSKReadRole();
        org.apache.kafka.clients.consumer.KafkaConsumer<String, String> consumer = new org.apache.kafka.clients.consumer.KafkaConsumer<String, String>(getConsumerConfig());
        consumer.subscribe(Collections.singletonList(topic));
        int count = 0;
        while (true) {
            final ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
            printConsumerQuotaMetrics(consumer);
            for (final ConsumerRecord<String, String> record : records) {
                logger.info("Topic: " + record.topic() + ",Key: " + record.key() +
                        ",Value: " + record.value() + ", Offset: " + record.offset() + ", Partition: " + record.partition());
            }
        }
    }
    /**
     * This prints Kafka client metrics on the console and stores metrics data as custom metrics in CloudWatch.
     */
    private void printConsumerQuotaMetrics(org.apache.kafka.clients.consumer.KafkaConsumer<String, String> consumer){
        if(printConsumerQuotaMetrics != null && printConsumerQuotaMetrics.trim().equalsIgnoreCase("N"))
            return;
        if (consumerFetchThrottleTimeAvgMetricName == null && consumerFetchThrottleTimeMaxMetricName == null)
        {
            Map<String, String> tags = new HashMap<String, String>();
            tags.put("client-id", this.clientId);
            consumerFetchThrottleTimeAvgMetricName = new MetricName("fetch-throttle-time-avg",
                    "consumer-fetch-manager-metrics",
                    "The average throttle time in ms",
                    tags);
            consumerFetchThrottleTimeMaxMetricName = new MetricName("fetch-throttle-time-max",
                    "consumer-fetch-manager-metrics",
                    "The maximum throttle time in ms",
                    tags);
        }
        Metric consumerFetchThrottleTimeAvgMetric = (Metric)consumer.metrics().get(consumerFetchThrottleTimeAvgMetricName);
        Metric consumerFetchThrottleTimeMaxMetric = (Metric)consumer.metrics().get(consumerFetchThrottleTimeMaxMetricName);
        Double consumerFetchThrottleTimeAvg = (Double)consumerFetchThrottleTimeAvgMetric.metricValue();
        Double consumerFetchThrottleTimeMax = (Double)consumerFetchThrottleTimeMaxMetric.metricValue();
        logger.info("fetch-throttle-time-avg -- > metric --> " + consumerFetchThrottleTimeAvgMetric.metricValue());
        logger.info("fetch-throttle-time-max -- > metric --> " + consumerFetchThrottleTimeMaxMetric.metricValue());

        if(!consumerFetchThrottleTimeAvg.isNaN())
            putMetData("fetch-throttle-time-avg", consumerFetchThrottleTimeAvg, this.dimensionName, this.dimensionValue, this.namespace);
        if(!consumerFetchThrottleTimeMax.isNaN())
            putMetData("fetch-throttle-time-max", consumerFetchThrottleTimeMax, this.dimensionName, this.dimensionValue, this.namespace);
    }
    /**
     * Configures Kafka consumer configuration.
     * @return java.util.Properties Kafka consumer configuration
     */
    private Properties getConsumerConfig() {
        String SASL_JAAS_CONFIG = "software.amazon.msk.auth.iam.IAMLoginModule required;";
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, this.consumerGroup);
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, this.clientId);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(SaslConfigs.SASL_JAAS_CONFIG,  SASL_JAAS_CONFIG);
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
        props.put(SaslConfigs.SASL_MECHANISM,  "AWS_MSK_IAM");
        props.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS,  "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        return props;
    }
    /**
     * Assumes MSK Read IAM role specified as command-line argument. 
     * When calling the assumeRole API, it uses the consumer's secret role session name.
     * @return Nothing
     */
    private void assumeMSKReadRole() {
        AssumeRoleRequest roleRequest = AssumeRoleRequest.builder()
                .roleArn(this.assumeRoleARN)
                .roleSessionName(this.roleSessionName)
                .build();
        AssumeRoleResponse roleResponse = stsClient.assumeRole(roleRequest);
        Credentials myCreds = roleResponse.credentials();
        System.setProperty("aws.accessKeyId", myCreds.accessKeyId());
        System.setProperty("aws.secretKey", myCreds.secretAccessKey());
        System.setProperty("aws.secretAccessKey", myCreds.secretAccessKey());
        System.setProperty("aws.sessionToken", myCreds.sessionToken());

        /*
        After setting up the temporary credentials in the system property,
        initialize the CloudWatch Client with the SystemPropertyCredentialsProvider
         */
        Region region = Region.of(regionName);
        if(!Region.regions().contains(region))
            throw new RuntimeException("Region : " + regionName + " is invalid.");
        cwClient = CloudWatchClient.builder().region(region).credentialsProvider(SystemPropertyCredentialsProvider.create()).build();
    }
    public void putMetData(String metricName,
                           Double dataPoint,
                           String dimensionName,
                           String dimensionValue,
                           String namespace) {
        Dimension dimension = Dimension.builder()
                .name(dimensionName)
                .value(dimensionValue)
                .build();

        // Set an Instant object
        String time = ZonedDateTime.now( ZoneOffset.UTC ).format( DateTimeFormatter.ISO_INSTANT );
        Instant instant = Instant.parse(time);

        MetricDatum datum = MetricDatum.builder()
                .metricName(metricName)
                .unit(StandardUnit.NONE)
                .value(dataPoint)
                .timestamp(instant)
                .dimensions(dimension).build();

        PutMetricDataRequest request = PutMetricDataRequest.builder()
                .namespace(namespace)
                .metricData(datum).build();

        cwClient.putMetricData(request);
    }
}
