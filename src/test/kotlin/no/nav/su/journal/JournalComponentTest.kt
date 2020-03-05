package no.nav.su.journal

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.AnythingPattern
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.withTestApplication
import io.ktor.util.KtorExperimentalAPI
import no.nav.su.meldinger.kafka.Topics.SOKNAD_TOPIC
import no.nav.su.meldinger.kafka.soknad.NySoknad
import no.nav.su.meldinger.kafka.soknad.NySoknadMedSkyggesak
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Duration.ofSeconds

@KtorExperimentalAPI
class JournalComponentTest {

    companion object {
        private val wireMockServer: WireMockServer = WireMockServer(WireMockConfiguration.options().dynamicPort())
        private val stsStub = StsStub()

        fun stubJournalpost(){
            WireMock.stubFor(
                WireMock.post(WireMock.urlPathEqualTo("/rest/journalpostapi/v1/journalpost"))
                    .withHeader(HttpHeaders.Authorization, WireMock.equalTo("Bearer $STS_TOKEN"))
                    .withHeader("Nav-Consumer-Id", WireMock.equalTo("supstonad"))
                    .withHeader("Nav-Call-Id", AnythingPattern())
                    .willReturn(
                        WireMock.okJson("""
                            {
                              "dokumenter": [
                                {
                                  "brevkode": "NAV 14-05.09",
                                  "dokumentInfoId": 123,
                                  "tittel": "Søknad om foreldrepenger ved fødsel"
                                }
                              ],
                              "journalpostId": 12345678,
                              "journalpostferdigstilt": true,
                              "journalstatus": "ENDELIG",
                              "melding": "null"
                            }
                        """.trimIndent())
                    )
            )
        }

        @BeforeAll
        @JvmStatic
        fun start() {
            wireMockServer.apply {
                start()
                val client = WireMock.create().port(wireMockServer.port()).build()
                WireMock.configureFor(client)
            }
            WireMock.stubFor(stsStub.stubbedSTS())
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
    fun `når vi får en melding om en søknad som har blitt tilordnet en skyggesak i Gsak, men ikke journalført, så skal vi journalføre søknaden`(){
        withTestApplication({
            testEnv(wireMockServer)
            sujournal()
        }) {
            val kafkaConfig = KafkaConfigBuilder(environment.config)
            val producer = KafkaProducer(kafkaConfig.producerConfig(), StringSerializer(), StringSerializer())
            producer.send(
                NySoknadMedSkyggesak(sakId = "1", soknadId = "1", soknad = """{"key":"value"}""", fnr = "12345678910", aktoerId = "1234567891011", gsakId = "6")
                    .toProducerRecord(SOKNAD_TOPIC))
            Thread.sleep(2000)
            wireMockServer.verify(1, WireMock.postRequestedFor(urlEqualTo("/rest/journalpostapi/v1/journalpost")))
        }
    }

    @Test
    fun `ignorerer søknader uten skyggesak i Gsak`(){
        withTestApplication({
            testEnv(wireMockServer)
            sujournal()
        }) {
            val kafkaConfig = KafkaConfigBuilder(environment.config)
            val producer = KafkaProducer(kafkaConfig.producerConfig(), StringSerializer(), StringSerializer())
            producer.send(
                NySoknad(sakId = "2", soknadId = "1", soknad = """{"key":"value"}""", fnr = "12345678910", aktoerId = "1234567891011")
                    .toProducerRecord(SOKNAD_TOPIC))
            Thread.sleep(2000)
            wireMockServer.verify(0, WireMock.postRequestedFor(urlEqualTo("/rest/journalpostapi/v1/journalpost")))
        }
    }
}
