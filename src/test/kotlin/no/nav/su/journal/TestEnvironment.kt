package no.nav.su.journal

import com.github.tomakehurst.wiremock.WireMockServer
import io.ktor.application.Application
import io.ktor.config.MapApplicationConfig
import io.ktor.util.KtorExperimentalAPI

const val STS_USERNAME = "srvsupstonad"
const val STS_PASSWORD = "supersecret"

@KtorExperimentalAPI
fun Application.testEnv(wireMockServer: WireMockServer) {
    val baseUrl = wireMockServer.baseUrl()
    (environment.config as MapApplicationConfig).apply {
        put("sts.url", baseUrl)
        put("sts.username", STS_USERNAME)
        put("sts.password", STS_PASSWORD)
        put("dokarkiv.url", baseUrl)
        put("dokarkiv.skarp", "true")
        put("kafka.username", "kafkaUser")
        put("kafka.password", "kafkaPassword")
        put("kafka.bootstrap", EmbeddedKafka.kafkaInstance.brokersURL)
        put("kafka.trustStorePath", "")
        put("kafka.trustStorePassword", "")
    }
}