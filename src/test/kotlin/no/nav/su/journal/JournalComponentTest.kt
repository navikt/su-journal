package no.nav.su.journal

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.AnythingPattern
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpHeaders.XCorrelationId
import io.ktor.server.testing.withTestApplication
import io.ktor.util.KtorExperimentalAPI
import no.nav.su.journal.EmbeddedKafka.Companion.kafkaConsumer
import no.nav.su.journal.EmbeddedKafka.Companion.kafkaProducer
import no.nav.su.meldinger.kafka.Topics.SØKNAD_TOPIC
import no.nav.su.meldinger.kafka.soknad.NySøknad
import no.nav.su.meldinger.kafka.soknad.NySøknadMedJournalId
import no.nav.su.meldinger.kafka.soknad.NySøknadMedSkyggesak
import no.nav.su.meldinger.kafka.soknad.SøknadMelding
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.fail

@KtorExperimentalAPI
@ExperimentalStdlibApi
class JournalComponentTest {

    companion object {
        private val wireMockServer: WireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
        private val stsStub = StsStub()

        val journalpostId = "12345678"
        const val pdf = "[B@8ee5af"

        fun stubSuPdfGen() {
            stubFor(
                post(urlPathEqualTo(suPdfGenPath))
                    .withHeader(XCorrelationId, AnythingPattern())
                    .willReturn(
                        ok().withBody(pdf)
                    )
            )
        }

        fun stubJournalpost() {
            stubFor(
                post(urlPathEqualTo(dokarkivPath))
                    .withHeader(HttpHeaders.Authorization, equalTo("Bearer $STS_TOKEN"))
                    .withHeader(XCorrelationId, AnythingPattern())
                    .willReturn(
                        okJson(
                            """
                            {
                              "dokumenter": [
                                {
                                  "brevkode": "NAV 14-05.09",
                                  "dokumentInfoId": 123,
                                  "tittel": "Søknad om foreldrepenger ved fødsel"
                                }
                              ],
                              "journalpostId": "$journalpostId",
                              "journalpostferdigstilt": true,
                              "journalstatus": "ENDELIG",
                              "melding": "null"
                            }
                        """.trimIndent()
                        )
                    )
            )
        }

        @BeforeAll
        @JvmStatic
        fun start() {
            wireMockServer.apply {
                start()
                val client = create().port(wireMockServer.port()).build()
                configureFor(client)
            }
            stubFor(stsStub.stubbedSTS())
            stubJournalpost()
        }

        @AfterAll
        @JvmStatic
        fun stop() {
            wireMockServer.stop()
        }
    }

    @BeforeEach
    fun resetWiremock() {
        wireMockServer.resetRequests()
    }

    @Test
    fun `når vi får en melding om en søknad som har blitt tilordnet en skyggesak i Gsak, men ikke journalført, så skal vi journalføre søknaden`() {
        withTestApplication({
            testEnv(wireMockServer)
            sujournal()
        }) {
            stubSuPdfGen()
            val sakId = "1"
            val nySøknadMedSkyggesak = NySøknadMedSkyggesak(
                sakId = sakId,
                søknadId = "2",
                søknad = """{"key":"value"}""",
                fnr = "12345678910",
                aktørId = "1234567891011",
                gsakId = "6"
            )
            kafkaProducer.send(nySøknadMedSkyggesak.toProducerRecord(SØKNAD_TOPIC))
            Thread.sleep(2000)
            val consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(1L))
            val søknadMelding = SøknadMelding.fromConsumerRecord(consumerRecords.single { it.key() == sakId && it.value().contains("journalId") })
            when (søknadMelding){
                is NySøknadMedJournalId -> { val søknadMedJournalId = søknadMelding
                    assertEquals(nySøknadMedSkyggesak.sakId, søknadMedJournalId.sakId)
                    assertEquals(nySøknadMedSkyggesak.søknadId, søknadMedJournalId.søknadId)
                    assertEquals(nySøknadMedSkyggesak.søknad, søknadMedJournalId.søknad)
                    assertEquals(nySøknadMedSkyggesak.fnr, søknadMedJournalId.fnr)
                    assertEquals(nySøknadMedSkyggesak.aktørId, søknadMedJournalId.aktørId)
                    assertEquals(nySøknadMedSkyggesak.gsakId, søknadMedJournalId.gsakId)
                    assertEquals(journalpostId, søknadMedJournalId.journalId)
                }
                else -> fail("Fant ingen melding om journalført søknad på kafka")
            }
            wireMockServer.verify(1, postRequestedFor(urlEqualTo(suPdfGenPath)))
            wireMockServer.verify(1,
                postRequestedFor(urlEqualTo(dokarkivPath))
                .withRequestBody(equalToJson(forventetJoarkRequestBody))
            )
        }
    }

    @Test
    fun `ignorerer søknader uten skyggesak i Gsak`() {
        withTestApplication({
            testEnv(wireMockServer)
            sujournal()
        }) {
            val kafkaConfig = KafkaConfigBuilder(environment.config)
            val producer = KafkaProducer(kafkaConfig.producerConfig(), StringSerializer(), StringSerializer())
            producer.send(
                NySøknad(
                    sakId = "2",
                    søknadId = "1",
                    søknad = """{"key":"value"}""",
                    fnr = "12345678910",
                    aktørId = "1234567891011"
                )
                    .toProducerRecord(SØKNAD_TOPIC)
            )
            Thread.sleep(2000)
            wireMockServer.verify(0, postRequestedFor(urlEqualTo("/rest/journalpostapi/v1/journalpost")))
        }
    }

    val forventetJoarkRequestBody = """
                    {
                      "avsenderMottaker": {
                        "id": 999263550,
                        "idType": "ORGNR",
                        "land": "Norge",
                        "navn": "NAV"
                      },
                      "behandlingstema": "ab0001",
                      "bruker": {
                        "id": 12345678910,
                        "idType": "FNR"
                      },
                      "datoMottatt": "2019-11-29",
                      "dokumenter": [
                        {
                          "brevkode": "NAV 14-05.09",
                          "dokumentKategori": "SOK",
                          "dokumentvarianter": [
                            {
                              "filnavn": "eksempeldokument.pdf",
                              "filtype": "PDFA",
                              "fysiskDokument": "$pdf",
                              "variantformat": "ARKIV"
                            }
                          ],
                          "tittel": "Søknad om foreldrepenger ved fødsel"
                        }
                      ],
                      "eksternReferanseId": "string",
                      "journalfoerendeEnhet": 9999,
                      "journalpostType": "INNGAAENDE",
                      "kanal": "NAV_NO",
                      "sak": {
                        "arkivsaksnummer": 111111111,
                        "arkivsaksystem": "GSAK",
                        "fagsakId": 111111111,
                        "fagsaksystem": "FS38",
                        "sakstype": "FAGSAK"
                      },
                      "tema": "FOR",
                      "tilleggsopplysninger": [
                        {
                          "nokkel": "bucid",
                          "verdi": "eksempel_verdi_123"
                        }
                      ],
                      "tittel": "Ettersendelse til søknad om foreldrepenger"
                    }
         """.trimIndent()

}
