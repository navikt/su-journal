package no.nav.su.journal

import io.ktor.config.ApplicationConfig
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.su.meldinger.kafka.Meldingsleser
import no.nav.su.meldinger.kafka.Topics.SØKNAD_TOPIC
import no.nav.su.meldinger.kafka.soknad.NySøknadMedSkyggesak
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.slf4j.LoggerFactory

@KtorExperimentalAPI
private val LOG = LoggerFactory.getLogger(SøknadConsumer::class.java)

@KtorExperimentalAPI
internal class SøknadConsumer(
    env: ApplicationConfig,
    private val pdfClient: PdfClient,
    private val dokarkivClient: DokArkiv
) {
    private val kafkaConfig = KafkaConfigBuilder(env)
    private val kafkaProducer = KafkaProducer<String, String>(
        kafkaConfig.producerConfig(),
        StringSerializer(),
        StringSerializer()
    )
    private val meldingsleser = Meldingsleser(env.kafkaMiljø(), Metrics)
    fun lesHendelser(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                meldingsleser.lesMelding<NySøknadMedSkyggesak> { nySøknadMedSkyggesak ->
                    val soknadPdf = pdfClient.genererPdf(nySøknadMedSkyggesak = nySøknadMedSkyggesak)
                    val journalPostId = dokarkivClient.opprettJournalpost(
                        nySøknadMedSkyggesak = nySøknadMedSkyggesak,
                        pdf = soknadPdf
                    )
                    val søknadMedJournalId = nySøknadMedSkyggesak.medJournalId(journalId = journalPostId)
                    kafkaProducer.send(søknadMedJournalId.toProducerRecord(SØKNAD_TOPIC))
                }
            }
        }
    }
}
