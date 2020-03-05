package no.nav.su.journal

import io.ktor.application.Application
import io.ktor.config.ApplicationConfig
import io.ktor.http.HttpHeaders.XCorrelationId
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.su.journal.Metrics.messageProcessed
import no.nav.su.journal.Metrics.messageRead
import no.nav.su.meldinger.kafka.Topics.SOKNAD_TOPIC
import no.nav.su.meldinger.kafka.headersAsString
import no.nav.su.meldinger.kafka.soknad.NySoknadMedSkyggesak
import no.nav.su.meldinger.kafka.soknad.SoknadMelding
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.*

val LOG = LoggerFactory.getLogger(Application::class.java)

@KtorExperimentalAPI
internal class SøknadConsumer(env: ApplicationConfig, private val dokarkivClient: DokArkiv) {
    private val LOG = LoggerFactory.getLogger(SøknadConsumer::class.java)
    private val kafkaConfig = KafkaConfigBuilder(env)
    private val kafkaConsumer = KafkaConsumer(
        kafkaConfig.consumerConfig(),
        StringDeserializer(),
        StringDeserializer()
    ).also {
        it.subscribe(listOf(SOKNAD_TOPIC))
    }

    private val kafkaProducer = KafkaProducer<String, String>(
        kafkaConfig.producerConfig(),
        StringSerializer(),
        StringSerializer()
    )

    fun lesHendelser(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                kafkaConsumer.poll(Duration.of(100, ChronoUnit.MILLIS))
                    .onEach {
                        it.logMessage()
                        messageRead()
                    }
                    .filter { SoknadMelding.fromConsumerRecord(it) is NySoknadMedSkyggesak }
                    .map { Pair(SoknadMelding.fromConsumerRecord(it) as NySoknadMedSkyggesak, it.headersAsString()) }
                    .forEach {
                        val message = it.first
                        val correlationId = it.second.getOrDefault(XCorrelationId, UUID.randomUUID().toString())
                        val journalPostId = dokarkivClient.opprettJournalpost(it.first.value(),correlationId)
                        kafkaProducer.send(message.asJournalPost(journalPostId).toProducerRecord(SOKNAD_TOPIC, it.second))
                        messageProcessed()
                    }
            }
        }
    }
}

private fun NySoknadMedSkyggesak.asJournalPost(journalPostId: String) = NySoknadMedSkyggesak(sakId = sakId, aktoerId = aktoerId, soknadId = soknadId, soknad = soknad, fnr = fnr, gsakId = journalPostId)

private fun ConsumerRecord<String, String>.logMessage() {
    LOG.info("Polled message: topic:${this.topic()}, key:${this.key()}, value:${this.value()}: $XCorrelationId:${this.headersAsString()[XCorrelationId]}")
}