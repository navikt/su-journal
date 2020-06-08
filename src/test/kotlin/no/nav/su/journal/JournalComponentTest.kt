package no.nav.su.journal

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.*
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.withTestApplication
import io.ktor.util.KtorExperimentalAPI
import no.nav.su.journal.EmbeddedKafka.Companion.kafkaConsumer
import no.nav.su.journal.EmbeddedKafka.Companion.kafkaProducer
import no.nav.su.meldinger.kafka.Topics.SØKNAD_TOPIC
import no.nav.su.meldinger.kafka.soknad.NySøknad
import no.nav.su.meldinger.kafka.soknad.NySøknadMedJournalId
import no.nav.su.meldinger.kafka.soknad.SøknadInnholdTestdataBuilder
import no.nav.su.meldinger.kafka.soknad.SøknadMelding
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
        val søknadInnholdPdf = søknadInnhold.toJson().toByteArray()
        val aktorId = "9876543210"
        val sakId = "1"


        fun stubSuPdfGen() = post(urlPathEqualTo(suPdfGenPath))
                .withHeader(HttpHeaders.XCorrelationId, equalTo(correlationId))
                .willReturn(ok().withBody(søknadInnholdPdf))

        fun stubJournalpost() = post(urlPathEqualTo(dokarkivPath))
                .withQueryParam("forsoekFerdigstill", equalTo("true"))
                .withHeader(HttpHeaders.Authorization, equalTo("Bearer $STS_TOKEN"))
                .withHeader(HttpHeaders.ContentType, equalTo(ContentType.Application.Json.toString()))
                .withHeader(HttpHeaders.Accept, equalTo(ContentType.Application.Json.toString()))
                .withHeader(HttpHeaders.XCorrelationId, equalTo(correlationId))
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
                    """.trimIndent()))

        @BeforeAll
        @JvmStatic
        fun start() {
            wireMockServer.start()
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
        wireMockServer.stubFor(stsStub.stubbedSTS())
        wireMockServer.stubFor(stubSuPdfGen())
        wireMockServer.stubFor(stubJournalpost())
    }

    @Test
    fun `når vi får en melding om en søknad som har blitt tilordnet en skyggesak i Gsak, men ikke journalført, så skal vi journalføre søknaden`() {
        withTestApplication({
            testEnv(wireMockServer)
            sujournal()
        }) {
            val nySøknad = NySøknad(
                    sakId = sakId,
                    søknadId = "1",
                    søknad = søknadInnhold.toJson(),
                    fnr = søknadInnhold.personopplysninger.fnr,
                    aktørId = aktorId,
                    correlationId = correlationId
            )
            kafkaProducer.send(nySøknad.toProducerRecord(SØKNAD_TOPIC)).get()
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
            wireMockServer.verify(1, postRequestedFor(urlPathEqualTo(suPdfGenPath)))
            wireMockServer.verify(1, postRequestedFor(urlPathEqualTo(dokarkivPath)))
            val requestBody = String(wireMockServer.allServeEvents.first { it.request.url.contains(dokarkivPath) }.request.body)
            val nySøknadKafka = SøknadMelding.fromConsumerRecord(consumerRecords
                    .single { it.key() == sakId && !it.value().contains("journalId") }
            ) as NySøknad //Krumspring for equality sjekk. Tekst som har vært ser/deser noen ganger er ikke nødvendigvis helt lik original input.
            assertEquals(JSONObject(requestBody).toString(), JSONObject(forventetRequest(nySøknadKafka, søknadInnholdPdf)).toString())
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
            wireMockServer.verify(0, postRequestedFor(urlPathEqualTo(dokarkivPath)))
        }
    }

    fun forventetRequest(nySøknad: NySøknad, pdf: ByteArray) = """
        {
          "tittel": "Søknad om supplerende stønad for uføre flyktninger",
          "journalpostType": "INNGAAENDE",
          "tema": "SUP",
          "kanal": "NAV_NO",
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
                  "fysiskDokument": "${Base64.getEncoder().encodeToString(pdf)}",
                  "variantformat": "ARKIV"
                },
                {
                  "filtype": "JSON",
                  "fysiskDokument": "${Base64.getEncoder().encodeToString(nySøknad.søknad.toByteArray())}",
                  "variantformat": "ORIGINAL"
                }
              ]
            }
          ]
        }
    """.trimIndent()
}