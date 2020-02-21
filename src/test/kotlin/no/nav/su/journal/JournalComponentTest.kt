package no.nav.su.journal

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.core.WireMockConfiguration
import com.github.tomakehurst.wiremock.matching.AnythingPattern
import io.ktor.http.HttpHeaders
import io.ktor.server.testing.withTestApplication
import io.ktor.util.KtorExperimentalAPI
import no.nav.su.journal.KafkaConfigBuilder.Topics.SOKNAD_TOPIC
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

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

    @Test
    fun `når vi får en melding om en søknad som har blitt tilordnet en skyggeskak i Gsak, men ikke journalført, så skal vi journalføre søknaden`(){
        withTestApplication({
            testEnv(wireMockServer)
            sujournal()
        }) {
            val kafkaConfig = KafkaConfigBuilder(environment.config)
            val producer = KafkaProducer(kafkaConfig.producerConfig(), StringSerializer(), StringSerializer())
            producer.send(ProducerRecord(SOKNAD_TOPIC,"""
                {
                    "soknadId":"",
                    "sakId":"",
                    "soknad":""
                }
            """.trimIndent()))
            Thread.sleep(2000)
            // TODO
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
            producer.send(ProducerRecord(SOKNAD_TOPIC,"""
                {
                    "soknadId":"1",
                    "sakId":"",
                    "soknad":{
                        innhold: "hei"
                    }
                }
            """.trimIndent()))
            // TODO
        }
    }


}