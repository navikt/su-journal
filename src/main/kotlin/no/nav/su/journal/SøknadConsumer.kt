package no.nav.su.journal

import io.ktor.config.ApplicationConfig
import io.ktor.util.KtorExperimentalAPI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import no.nav.su.meldinger.kafka.Topics.SØKNAD_TOPIC
import no.nav.su.meldinger.kafka.soknad.NySøknad

@KtorExperimentalAPI
internal class SøknadConsumer(
    env: ApplicationConfig,
    private val pdfClient: PdfClient,
    private val dokarkivClient: DokArkiv
) {
    private val kafkaProducer = env.kafkaMiljø().producer()
    private val meldingsleser = env.kafkaMiljø().meldingsleser(Metrics)
    fun lesHendelser(scope: CoroutineScope) {
        scope.launch {
            while (isActive) {
                meldingsleser.lesMelding<NySøknad> {
                    val soknadPdf = pdfClient.genererPdf(nySøknad = it)
                    val journalPostId = dokarkivClient.opprettJournalpost(
                        nySøknad = it,
                        pdf = soknadPdf
                    )
                    val søknadMedJournalId = it.medJournalId(journalId = journalPostId)
                    kafkaProducer.send(søknadMedJournalId.toProducerRecord(SØKNAD_TOPIC))
                }
            }
        }
    }
}
