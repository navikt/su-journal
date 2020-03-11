package no.nav.su.journal

import io.ktor.config.ApplicationConfig
import io.ktor.util.KtorExperimentalAPI
import no.nav.su.meldinger.kafka.KafkaMiljø
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.slf4j.LoggerFactory
import java.io.File
import java.util.*

@KtorExperimentalAPI
fun ApplicationConfig.kafkaMiljø() = KafkaMiljø(
    groupId = getProperty("kafka.groupId"),
    username = getProperty("kafka.username"),
    password = getProperty("kafka.password"),
    trustStorePath = getProperty("kafka.username"),
    trustStorePassword = getProperty("kafka.trustStorePassword"),
    commitInterval = getProperty("kafka.commitInterval"),
    bootstrap = getProperty("kafka.bootstrap")
)
