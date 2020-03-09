package no.nav.su.journal

import io.ktor.config.ApplicationConfig
import io.ktor.http.HttpHeaders.XCorrelationId
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.su.journal.Metrics.messageProcessed
import no.nav.su.journal.Metrics.messageRead
import no.nav.su.meldinger.kafka.Topics.SØKNAD_TOPIC
import no.nav.su.meldinger.kafka.headersAsString
import no.nav.su.meldinger.kafka.soknad.NySøknadMedSkyggesak
import no.nav.su.meldinger.kafka.soknad.SøknadMelding
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.temporal.ChronoUnit

@KtorExperimentalAPI
private val LOG = LoggerFactory.getLogger(SøknadConsumer::class.java)

@KtorExperimentalAPI
internal class SøknadConsumer(
    env: ApplicationConfig,
    private val pdfClient: PdfClient,
    private val dokarkivClient: DokArkiv
) {
    private val kafkaConfig = KafkaConfigBuilder(env)
    private val kafkaConsumer = KafkaConsumer(
        kafkaConfig.consumerConfig(),
        StringDeserializer(),
        StringDeserializer()
    ).also {
        it.subscribe(listOf(SØKNAD_TOPIC))
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
                    .filter { SøknadMelding.fromConsumerRecord(it) is NySøknadMedSkyggesak }
                    .map { SøknadMelding.fromConsumerRecord(it) as NySøknadMedSkyggesak }
                    .forEach { nySøknadMedSkyggesak ->
                        val soknadPdf = pdfClient.genererPdf(nySøknadMedSkyggesak = nySøknadMedSkyggesak)
                        val journalPostId = dokarkivClient.opprettJournalpost(
                            nySøknadMedSkyggesak = nySøknadMedSkyggesak,
                            pdf = soknadPdf
                        )
                        val søknadMedJournalId = nySøknadMedSkyggesak.medJournalId(journalId = journalPostId)
                        kafkaProducer.send(søknadMedJournalId.toProducerRecord(SØKNAD_TOPIC))
                        messageProcessed()
                    }
            }
        }
    }
}

@KtorExperimentalAPI
private fun ConsumerRecord<String, String>.logMessage() {
    LOG.info("Polled message: topic:${this.topic()}, key:${this.key()}, value:${this.value()}: $XCorrelationId:${this.headersAsString()[XCorrelationId]}")
}