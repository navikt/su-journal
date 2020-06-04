package no.nav.su.journal

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpHeaders.XCorrelationId
import io.ktor.server.testing.withTestApplication
import io.ktor.util.KtorExperimentalAPI
import no.nav.su.journal.EmbeddedKafka.Companion.kafkaConsumer
import no.nav.su.journal.EmbeddedKafka.Companion.kafkaProducer
import no.nav.su.meldinger.kafka.Topics.SØKNAD_TOPIC
import no.nav.su.meldinger.kafka.soknad.*
import org.json.JSONObject
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.fail

@KtorExperimentalAPI
@ExperimentalStdlibApi
class JournalComponentTest {

    companion object {
        private val wireMockServer: WireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
        private val stsStub = StsStub()

        val correlationId = "correlationId"
        val journalpostId = "12345678"
        val søknadInnhold = SøknadInnholdTestdataBuilder.build()
        val søknadInnholdPdf = Base64.getEncoder().encodeToString(søknadInnhold.toJson().toByteArray())
        val aktorId = "9876543210"
        val sakId = "1"


        fun stubSuPdfGen() {
            stubFor(post(urlPathEqualTo(suPdfGenPath))
                    .withHeader(XCorrelationId, equalTo(correlationId))
                    .willReturn(ok().withBody(søknadInnholdPdf)))
        }

        fun stubJournalpost() {
            stubFor(post(urlPathEqualTo(dokarkivPath))
                    .withHeader(HttpHeaders.Authorization, equalTo("Bearer $STS_TOKEN"))
                    .withHeader(XCorrelationId, equalTo(correlationId))
                    .willReturn(okJson("""
                        {
                          "journalpostId": "$journalpostId",
                          "journalpostferdigstilt": true,
                          "dokumenter": [
                            {
                              "dokumentInfoId": "485227498",
                              "tittel": "Søknad om supplerende stønad for uføre flyktninger"
                            }
                          ]
                        }
                    """.trimIndent())))
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
            val nySøknadMedSkyggesak = NySøknadMedSkyggesak(
                    sakId = sakId,
                    søknadId = "1",
                    søknad = søknadInnhold.toJson(),
                    fnr = søknadInnhold.personopplysninger.fnr,
                    aktørId = aktorId,
                    gsakId = "6",
                    correlationId = correlationId
            )
            kafkaProducer.send(nySøknadMedSkyggesak.toProducerRecord(SØKNAD_TOPIC)).get()
            Thread.sleep(2000)
            val consumerRecords = kafkaConsumer.poll(Duration.ofSeconds(1L))
            val søknadMelding = SøknadMelding.fromConsumerRecord(consumerRecords.single { it.key() == sakId && it.value().contains("journalId") })
            when (søknadMelding) {
                is NySøknadMedJournalId -> {
                    val søknadMedJournalId = søknadMelding
                    assertEquals(journalpostId, søknadMedJournalId.journalId)
                }
                else -> fail("Fant ingen melding om journalført søknad på kafka")
            }
            wireMockServer.verify(1, postRequestedFor(urlEqualTo(suPdfGenPath)))
            wireMockServer.verify(1, postRequestedFor(urlEqualTo(dokarkivPath)))
            val requestBody = String(wireMockServer.allServeEvents.first { it.request.url == dokarkivPath }.request.body)
            assertEquals(JSONObject(requestBody).toString(), JSONObject(forventetJoarkRequestBody).toString())
        }
    }

    @Test
    fun `ignorerer søknader uten skyggesak i Gsak`() {
        withTestApplication({
            testEnv(wireMockServer)
            sujournal()
        }) {
            val producer = environment.config.kafkaMiljø().producer()
            producer.send(
                    NySøknad(
                            sakId = "2",
                            søknadId = "1",
                            søknad = """{"key":"value"}""",
                            fnr = "12345678910",
                            aktørId = "12345678910",
                            correlationId = "correlationId"
                    )
                            .toProducerRecord(SØKNAD_TOPIC)
            ).get()
            Thread.sleep(2000)
            wireMockServer.verify(0, postRequestedFor(urlEqualTo("/rest/journalpostapi/v1/journalpost")))
        }
    }

    val forventetJoarkRequestBody = """
        {
          "tittel": "Søknad om supplerende stønad for uføre flyktninger",
          "journalpostType": "INNGAAENDE",
          "tema": "SUP",
          "behandlingstema": "ab0268",
          "journalfoerendeEnhet": "9999",
          "avsenderMottaker": {
            "id": "${søknadInnhold.personopplysninger.fnr}",
            "idType": "FNR",
            "navn": "Nordmann, Ola Erik"
          },
          "bruker": {
            "id": "${søknadInnhold.personopplysninger.fnr}",
            "idType": "FNR"
          },
          "sak": {
            "fagsakId": "$sakId",
            "fagsaksystem": "SUPSTONAD",
            "sakstype": "FAGSAK"
          },
          "dokumenter": [
            {
              "tittel": "Søknad om supplerende stønad for uføre flyktninger",
              "dokumentvarianter": [
                {
                  "filtype": "PDFA",
                  "fysiskDokument": "$søknadInnholdPdf",
                  "variantformat": "ARKIV"
                },
                {
                  "filtype": "JSON",
                  "fysiskDokument": ${søknadInnhold.toJson()},
                  "variantformat": "ORIGINAL"
                }
              ]
            }
          ]
        }
    """.trimIndent()

}