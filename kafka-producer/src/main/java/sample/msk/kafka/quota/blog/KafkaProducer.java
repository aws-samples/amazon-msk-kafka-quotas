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
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.common.Metric;
import org.apache.kafka.common.MetricName;
import org.apache.kafka.common.config.SaslConfigs;
import org.apache.kafka.common.serialization.StringSerializer;
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

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

public class KafkaProducer {

    private final static Logger logger = LoggerFactory.getLogger(KafkaProducer.class.getName());

    @Parameter(names = {"--help", "-h"}, help = true)
    private boolean help = false;
    @Parameter(names={"--bootstrap-servers"},description="Amazon MSK cluster bootstrap servers IAM endpoint")
    private String bootstrapServers;
    @Parameter(names={"--assume-role-arn"},description="IAM role ARN in a Shared Services Account that the producer will assume")
    private String assumeRoleARN;
    @Parameter(names={"--region"},description="AWS Region where you want to point AWS STS client to e.g. us-east-1 Default is us-east-1")
    private String regionName = "ap-southeast-2";
    @Parameter(names={"--topic-name"},description="Kafka topic name on MSK cluster. Default is topic-A")
    private String topic = "Topic-B";
    @Parameter(names={"--num-messages"},description="Number of messages that producer should send. Default is 10000")
    private String str_numOfMessages = "100000000";
    @Parameter(names={"--role-session-name"},description="IAM role session name for the STS assumerole call")
    private String roleSessionName;
    @Parameter(names={"--client-id"},description="Producer application client id")
    private String clientId;
    @Parameter(names={"--print-producer-quota-metrics"},description="Flag Y or N to decide whether to print producer quota metrics")
    private String printProducerQuotaMetrics = "N";
    @Parameter(names={"--producer-type"},description="Specify producer type you want to start, valid values 'async' or 'sync'")
    private String producerType = "sync";
    @Parameter(names={"--cw-dimension-name"},description="Specify CloudWatch dimension name")
    private String dimensionName = "ProducerApp";
    @Parameter(names={"--cw-dimension-value"},description="Specify CloudWatch dimension value")
    private String dimensionValue;
    @Parameter(names={"--cw-namespace"},description="Specify CloudWatch namespace")
    private String namespace;

    private StsClient stsClient = null;
    private CloudWatchClient cwClient = null;

    private MetricName producerThrottleTimeAvgMetricName = null;
    private MetricName produceThrottleTimeMaxMetricName = null;

    public static void main(String[] args) throws InterruptedException, ExecutionException {
        KafkaProducer producer = new KafkaProducer();
        JCommander jc = JCommander.newBuilder()
                .addObject(producer)
                .build();
        jc.parse(args);
        if (producer.help){
            jc.usage();
            return;
        }
        producer.startProducer();
    }
    public KafkaProducer(){
        Region region = Region.of(regionName);
        if(!Region.regions().contains(region))
            throw new RuntimeException("Region : " + regionName + " is invalid.");
        stsClient = StsClient.builder().region(region).build();
    }
    private void startProducer() throws ExecutionException, InterruptedException {
        if(this.producerType != null)
        {
            if(this.producerType.trim().equalsIgnoreCase("sync"))
                startProducerSync();
            else if(this.producerType.trim().equalsIgnoreCase("async"))
                startProducerAsync();
            else throw new RuntimeException("Producer type must be either 'sync' or 'async'");
        }
    }
    /**
     * Starts the Kafka producer. First, it assumes an IAM role in the Shared Services Account provided as a command line argument.
     * The specified number of events are then sent to the Kafka topic specified as a command line argument, by default topic-A.
     * @return Nothing
     */
    private void startProducerSync() throws InterruptedException, ExecutionException {
        logger.info("Starting producer *********");

        //Assume TopicB Write IAM role using a secret role session name
        assumeMSKWriteRole();
        org.apache.kafka.clients.producer.KafkaProducer<String, String> producer = new org.apache.kafka.clients.producer.KafkaProducer<String,String>(getProducerConfig());
        int numberOfMessages = Integer.valueOf(str_numOfMessages);
        logger.info("Starting to send records...");
        RecordMetadata recordMetadata;
        for(int i = 0;i < numberOfMessages;i ++)
        {
            String key = "key-" + i;
            String message = "We are testing Kafka quota with MSK Cluster enabled with IAM auth " + i;
            ProducerRecord<String, String> record = new ProducerRecord<String, String>(topic, key,message);
            recordMetadata = producer.send(record).get();
            printRecordMetaData(recordMetadata);
            printProducerQuotaMetrics(producer);
        }
    }

    /**
     * Starts the Kafka producer. First, it assumes an IAM role in the Shared Services Account provided as a command line argument.
     * The specified number of events are then sent to the Kafka topic specified as a command line argument, by default topic-A.
     * @return Nothing
     */
    private void startProducerAsync() throws InterruptedException {
        assumeMSKWriteRole();
        org.apache.kafka.clients.producer.KafkaProducer<String, String> producer = new org.apache.kafka.clients.producer.KafkaProducer<String,String>(getProducerConfig());
        int numberOfMessages = Integer.valueOf(str_numOfMessages);
        logger.info("Starting to send records...");
        for(int i = 0;i < numberOfMessages;i ++)
        {
            String key = "key-" + i;
            String message = "We are testing Kafka quota with MSK Cluster enabled with IAM auth " + i;
            ProducerRecord<String, String> record = new ProducerRecord<String, String>(topic, key,message);
            producer.send(record, new ProducerCallback());
            printProducerQuotaMetrics(producer);
        }
    }
    private void printProducerQuotaMetrics(org.apache.kafka.clients.producer.KafkaProducer<String, String> producer){
        if(printProducerQuotaMetrics != null && printProducerQuotaMetrics.trim().equalsIgnoreCase("N"))
            return;
        if (producerThrottleTimeAvgMetricName == null && produceThrottleTimeMaxMetricName == null) {
            Map<String,String> tags = new HashMap<String,String>();
            tags.put("client-id",this.clientId);
            producerThrottleTimeAvgMetricName = new MetricName("produce-throttle-time-avg",
                    "producer-metrics",
                    "The average time in ms a request was throttled by a broker",
                    tags);

            produceThrottleTimeMaxMetricName = new MetricName("produce-throttle-time-max",
                    "producer-metrics",
                    "The maximum time in ms a request was throttled by a broker",
                    tags);
        }
        Metric producerThrottleTimeAvgMetric = (Metric)producer.metrics().get(producerThrottleTimeAvgMetricName);
        Metric produceThrottleTimeMaxMetric = (Metric)producer.metrics().get(produceThrottleTimeMaxMetricName);
        Double producerThrottleTimeAvg = (Double)producerThrottleTimeAvgMetric.metricValue();
        Double produceThrottleTimeMax = (Double)produceThrottleTimeMaxMetric.metricValue();
        logger.info("produce-throttle-time-avg -- > metric --> " + " : " + producerThrottleTimeAvg);
        logger.info("produce-throttle-time-max -- > metric --> " + " : " + produceThrottleTimeMax);

        if(!producerThrottleTimeAvg.isNaN())
            putMetData("produce-throttle-time-avg",producerThrottleTimeAvg,this.dimensionName,this.dimensionValue,this.namespace);
        if(!produceThrottleTimeMax.isNaN())
            putMetData("produce-throttle-time-max",produceThrottleTimeMax,this.dimensionName,this.dimensionValue,this.namespace);
    }
    private void printRecordMetaData(RecordMetadata recordMetaData){
        logger.info("Received new metadata. \n" +
                "Topic:" + recordMetaData.topic() + "\n" +
                "Partition: " + recordMetaData.partition() + "\n" +
                "Offset: " + recordMetaData.offset() + "\n" +
                "Timestamp: " + recordMetaData.timestamp());
    }
    /**
     * Configures Kafka producer configuration.
     * @return java.util.Properties Kafka producer configuration
     */
    private Properties getProducerConfig() {
        String SASL_JAAS_CONFIG = "software.amazon.msk.auth.iam.IAMLoginModule required;";
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, this.bootstrapServers);
        props.put(ProducerConfig.ACKS_CONFIG, "-1");
        props.put(ProducerConfig.CLIENT_ID_CONFIG,this.clientId);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(SaslConfigs.SASL_JAAS_CONFIG, SASL_JAAS_CONFIG);
        props.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL");
        props.put(SaslConfigs.SASL_MECHANISM, "AWS_MSK_IAM");
        props.put(SaslConfigs.SASL_CLIENT_CALLBACK_HANDLER_CLASS, "software.amazon.msk.auth.iam.IAMClientCallbackHandler");
        return props;
    }
    /**
     * Assumes MSK Write IAM role from Shared Services Account provided as command line argument.
     * It uses producer's secret role session name while calling assumeRole API.
     * @return Nothing
     */
    private void assumeMSKWriteRole() {
        logger.info("Assuming role " + this.assumeRoleARN);
        logger.info("**************************************");
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
    private class ProducerCallback implements Callback {
        /**
         * This method is a callback method which gets
         * invoked when Kafka producer receives the messages delivery
         * acknowledgement.
         * @return Nothing
         */
        @Override
        public void onCompletion(RecordMetadata recordMetaData, Exception e){
            if (e == null) {

                logger.info("Received new metadata. \n" +
                        "Topic:" + recordMetaData.topic() + "\n" +
                        "Partition: " + recordMetaData.partition() + "\n" +
                        "Offset: " + recordMetaData.offset() + "\n" +
                        "Timestamp: " + recordMetaData.timestamp());

            }
            else {
                logger.info("There's been an error from the Producer side");
                e.printStackTrace();
            }
        }
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

